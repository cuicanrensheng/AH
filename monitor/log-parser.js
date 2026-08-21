'use strict';

/**
 * 日志分类解析器
 *
 * 根据 logcat 原始行解析出结构化日志，
 * 并根据 TAG 自动归类为：
 *   - crash    崩溃日志
 *   - parse    解析日志（虎牙源、直播源、播放列表）
 *   - player   播放器日志（播放状态、重试、解码）
 *   - source   源管理日志（源加载、健康检查、重定向）
 *   - security 安全日志（签名校验、完整性、反调试）
 *   - system   系统日志（开机启动、设备能力、缓存）
 *   - debug    调试日志（其他所有日志）
 */

// === TAG 分类映射表（基于项目实际代码） ===
const TAG_CATEGORY_MAP = {
  // 崩溃
  'CrashHandler':        'crash',
  'AndroidRuntime':      'crash',
  'libc':                'crash',
  'DEBUG':               'crash',
  'tombstoned':          'crash',

  // 解析
  'HuyaSDKParser':      'parse',
  'HuyaParser':          'parse',
  'HuyaSDKLogger':      'parse',
  'LiveSourceLoader':    'parse',
  'PlaylistParser':     'parse',

  // 播放器
  'TVPlayerManager':    'player',
  'HuyaStreamPlayer':   'player',
  'RedirectHttp':       'player',
  'AudioTrack':         'player',
  'MediaCodec':         'player',
  'ExoPlayer':          'player',
  'MediaSession':       'player',

  // 源管理
  'SourceDialogManager':  'source',
  'SourceHealthChecker':  'source',
  'SourceManager':        'source',
  'VariantManager':       'source',
  'RedirectConfig':      'source',

  // 安全
  'SecChk':              'security',
  'IntegrityCheck':      'security',
  'SecurityCore':        'security',

  // 系统
  'BootStartManager':   'system',
  'BootStartFgService': 'system',
  'BootStartReceiver':  'system',
  'BootReceiver':        'system',
  'BootJobService':     'system',
  'DeviceCapabilities':  'system',
  'DecoderModeManager':  'system',
  'HuyaCacheGov':        'system',
  'AppCacheInspector':   'system',
  'CacheManager':        'system',
  'MyApplication':       'system',
  'MainActivity':        'system',
};

// === 日志级别颜色 ===
const LEVEL_COLORS = {
  'V': '#888888',
  'D': '#9999cc',
  'I': '#66cc66',
  'W': '#ffcc66',
  'E': '#ff6666',
  'F': '#ff0000',
};

// === logcat threadtime 格式正则 ===
// 示例: "08-19 15:30:45.123  1234  5678 D TAG    : message text"
const LOGCAT_REGEX = /^(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d+)\s+(\d+)\s+(\d+)\s+([VDIWEF])\s+(\S+)\s*:\s*(.*)$/;

// === 崩溃特征正则 ===
const CRASH_PATTERNS = [
  /FATAL EXCEPTION/i,
  /AndroidRuntime.*FATAL/i,
  /signal \d+/i,
  /backtrace:/i,
  /E\/CrashHandler/i,
];

class LogParser {
  constructor() {
    this.stats = {
      crash: 0,
      parse: 0,
      player: 0,
      source: 0,
      security: 0,
      system: 0,
      debug: 0,
    };
  }

  /**
   * 解析单行 logcat 输出
   */
  parse(rawLine) {
    const match = rawLine.match(LOGCAT_REGEX);
    if (!match) {
      // 非标准格式行，归类为 debug
      return this._buildLogEntry({
        timestamp: '',
        pid: '',
        tid: '',
        level: 'D',
        tag: '',
        message: rawLine,
        category: 'debug',
        raw: rawLine,
      });
    }

    const [, timestamp, pid, tid, level, tag, message] = match;

    // 确定分类
    let category = TAG_CATEGORY_MAP[tag] || this._detectCategoryByContent(tag, message);

    // 崩溃特征检测（即使 TAG 不匹配也检测）
    if (category !== 'crash') {
      for (const pattern of CRASH_PATTERNS) {
        if (pattern.test(rawLine)) {
          category = 'crash';
          break;
        }
      }
    }

    // 统计
    this.stats[category]++;

    return this._buildLogEntry({
      timestamp,
      pid,
      tid,
      level,
      tag,
      message,
      category,
      raw: rawLine,
    });
  }

  /**
   * 根据内容特征分类
   */
  _detectCategoryByContent(tag, message) {
    const combined = (tag + ' ' + message).toLowerCase();

    if (/crash|fatal|exception|nullpointer|arrayindexoutofbounds|illegalstate/.test(combined)) {
      return 'crash';
    }
    if (/parse|解析|json|huya.*line|bitrate|stream.*url/.test(combined)) {
      return 'parse';
    }
    if (/play|player|buffer|render|surface|decode|codec|video|audio/.test(combined)) {
      return 'player';
    }
    if (/source|url|epg|live.*list|channel/.test(combined)) {
      return 'source';
    }
    if (/security|sign|verify|integrity|debug|ptrace|tamper/.test(combined)) {
      return 'security';
    }
    if (/boot|service|receiver|alarm|job|cache|device|screen/.test(combined)) {
      return 'system';
    }

    return 'debug';
  }

  /**
   * 构建日志条目
   */
  _buildLogEntry({ timestamp, pid, tid, level, tag, message, category, raw }) {
    return {
      id: `${Date.now()}-${Math.random().toString(36).substr(2, 9)}`,
      timestamp,
      pid,
      tid,
      level,
      levelName: this._levelName(level),
      tag,
      message,
      category,
      categoryLabel: this._categoryLabel(category),
      color: LEVEL_COLORS[level] || '#cccccc',
      raw,
      time: new Date().toISOString(),
    };
  }

  _levelName(level) {
    const names = { V: 'VERBOSE', D: 'DEBUG', I: 'INFO', W: 'WARN', E: 'ERROR', F: 'FATAL' };
    return names[level] || level;
  }

  _categoryLabel(category) {
    const labels = {
      crash: '崩溃',
      parse: '解析',
      player: '播放',
      source: '源管理',
      security: '安全',
      system: '系统',
      debug: '调试',
    };
    return labels[category] || category;
  }

  /**
   * 获取统计
   */
  getStats() {
    return { ...this.stats };
  }

  /**
   * 重置统计
   */
  resetStats() {
    for (const key of Object.keys(this.stats)) {
      this.stats[key] = 0;
    }
  }
}

module.exports = LogParser;
