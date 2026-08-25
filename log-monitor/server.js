const WebSocket = require('ws');
const express = require('express');
const cors = require('cors');
const http = require('http');
const https = require('https');
const path = require('path');
const fs = require('fs');
const os = require('os');
const { exec, spawn } = require('child_process');
const util = require('util');
const execAsync = util.promisify(exec);

const PORT = process.env.PORT || 3000;
const WS_PORT = process.env.WS_PORT || 3001;

const app = express();
app.use(cors());
// 普通 JSON：默认 100kb 太小，部分老前端（传 base64 小文件）还在走；但大 APK 必须用 application/octet-stream 二进制直传
app.use(express.json({ limit: '50mb' }));
app.use(express.urlencoded({ extended: true, limit: '50mb' }));
app.use(express.static(path.join(__dirname, 'public'), {
  setHeaders: (res, filePath) => {
    // 禁用缓存，确保每次加载最新代码
    res.setHeader('Cache-Control', 'no-store, no-cache, must-revalidate, proxy-revalidate');
    res.setHeader('Pragma', 'no-cache');
    res.setHeader('Expires', '0');
  }
}));

const server = http.createServer(app);
const wss = new WebSocket.Server({ server });

const devices = new Map();
const clientConnections = new Set();
const logs = [];
const MAX_LOGS = 5000;
const adbSessions = new Map();
const TV_LIVE_PACKAGE = 'com.tv.live';

const logBatchBuffer = [];
let logBatchTimer = null;
const LOG_BATCH_INTERVAL = 50;
const LOG_BATCH_MAX_SIZE = 100;
const APP_LOG_TAGS = 'TVLive:V CrashHandler:V LogServer:V TVPlayerManager:V LiveSourceLoader:V HuyaSDKLogger:V HuyaSDKParser:V HuyaStreamPlayer:V HuyaCacheGov:V BootReceiver:V BootStartManager:V BootStartReceiver:V BootJobService:V BootStartFgService:V SourceHealthChecker:V SourceDialogManager:V SecChk:V RedirectHttp:V DecoderModeManager:V DeviceCapabilities:V VariantManager:V AppCacheInspector:V BootStartForegroundService:V MyApplication:V SecurityCore:V TVLS:V MainActivity:V KEY_DEBUG:V TVPlayerManager:V MediaPlayer:V System.err:V System.out:V DEBUG:D';

// ========== ADB 通用 helper ==========

// 通过 adb devices -l 拿到当前真实设备列表（state==='device'）
async function listAliveAdbDevices() {
  return new Promise((resolve) => {
    exec(`"${ADB_PATH}" devices -l`, { timeout: 4000 }, (err, stdout) => {
      if (err) return resolve([]);
      const all = parseAdbDevices(stdout || '');
      resolve(all.filter(d => d.state === 'device'));
    });
  });
}

// 把「adb devices -l」的 stdout 解析成结构化数组；比之前各处分散正则更统一
function parseAdbDevices(stdout) {
  const lines = (stdout || '').trim().split('\n');
  const result = [];
  const seenSerials = new Set();
  for (let i = 1; i < lines.length; i++) {
    const line = lines[i].trim();
    if (!line) continue;
    const parts = line.split(/\s+/);
    if (parts.length < 2) continue;
    const serial = parts[0];
    const state = parts[1];
    if (seenSerials.has(serial)) continue;
    seenSerials.add(serial);
    const info = {};
    for (let j = 2; j < parts.length; j++) {
      const kv = parts[j].split(':');
      if (kv.length === 2) info[kv[0]] = kv[1];
    }
    result.push({
      serial,
      state,
      model: info.model || 'Unknown',
      device: info.device || '',
      product: info.product || '',
      isEmulator: serial.startsWith('emulator-') || serial.startsWith('127.0.0.1'),
      transportId: info.transport_id || info['transport_id'] || ''
    });
  }
  return result;
}

// 前端可能因为旧缓存 / 旧逻辑把 serial 做过字符替换（_→. 或 _→- 之类），
// 这里用「真实 adb devices 列表」对输入 serial 做一次容错匹配，返回可直接给 adb -s 的原始 serial。
// 匹配优先级：
//   1) 全相等（大小写敏感）→ 直接用
//   2) 去掉两端空白后相等
//   3) 把两端都「归一化」（非字母数字统一替换成单个占位符 + 去掉多余分隔符前缀）后包含/相等
//   4) 取第一个 guid 前缀相同的（AXWFVB... 这种设备串）
//   5) 仅一个在线设备时直接兜底它
async function resolveAdbSerial(input) {
  if (!input) return null;
  const raw = String(input).trim();
  const alive = await listAliveAdbDevices();
  if (alive.length === 0) return raw;

  if (alive.some(d => d.serial === input || d.serial === raw)) return raw;

  const norm = (s) => String(s || '')
    .replace(/^adb[_.-]?device[_.-]?/i, '')
    .replace(/[^a-zA-Z0-9]+/g, '_')
    .replace(/^_+|_+$/g, '');
  const nInput = norm(raw);

  // 精确归一化相等
  for (const d of alive) {
    if (norm(d.serial) === nInput) return d.serial;
  }
  // 归一化后相互包含
  for (const d of alive) {
    const nd = norm(d.serial);
    if (nInput && nd && (nd.includes(nInput) || nInput.includes(nd))) return d.serial;
  }
  // guid 片段匹配（取第一段 6+ 字母数字的 key）
  const keyMatch = nInput.match(/[A-Z0-9]{6,}/i);
  if (keyMatch) {
    const key = keyMatch[0];
    for (const d of alive) {
      if (d.serial.includes(key)) return d.serial;
    }
  }
  // 只剩一个设备时兜底（即使没匹配上也用它，总比 device not found 好）
  if (alive.length === 1) return alive[0].serial;
  return null;
}

// 为一个 serial 启动 logcat 抓取；如果该 serial 已经有运行中的 session，直接返回现有信息（幂等）
// 返回 { started: bool, sessionId, adbDeviceId, alreadyRunning: bool }
function ensureLogcatStartedForSerial(serial) {
  if (!serial) return { started: false, alreadyRunning: false };

  // 已存在 session 对应这个 serial？
  for (const [sId, s] of adbSessions) {
    if (s.serial === serial && s.connected && !s.manualDisconnect) {
      return { started: false, alreadyRunning: true, sessionId: sId, adbDeviceId: s.id };
    }
  }

  const sessionId = `adb_${Date.now()}_${Math.random().toString(36).substr(2, 6)}`;
  const adbDeviceId = `adb_device_${serial.replace(/[^a-zA-Z0-9]/g, '_')}`;
  const adb = `"${ADB_PATH}" -s "${serial}"`;
  const LOCAL_LOG_PORT = 19527 + (Math.floor(Math.random() * 1000));

  // 端口转发
  try {
    exec(`${adb} forward tcp:${LOCAL_LOG_PORT} tcp:9527`, (err) => {
      if (err) console.warn(`[ADB] Port forwarding failed for ${serial}: ${err.message}`);
      else console.log(`[ADB] Port forwarding set for ${serial}: localhost:${LOCAL_LOG_PORT} -> device:9527`);
    });
  } catch (_) {}

  // 检查 TV Live 是否已安装，已安装就启动一下
  exec(`${adb} shell pm list packages | findstr "${TV_LIVE_PACKAGE}"`, (err, stdout) => {
    const installed = !err && (stdout || '').includes(TV_LIVE_PACKAGE);
    const delay = installed ? 2000 : 500;
    if (installed) {
      exec(`${adb} shell am force-stop ${TV_LIVE_PACKAGE}`, () => {
        exec(`${adb} shell am start -n ${TV_LIVE_PACKAGE}/.MainActivity`, () => {
          console.log(`[ADB] Ensured TV Live is running on ${serial}`);
        });
      });
    }
    setTimeout(() => {
      console.log(`[ADB] Starting logcat for ${serial} (auto)`);
      startLogcatByTags(adb, serial, adbDeviceId, sessionId);
    }, delay);
  });

  devices.set(adbDeviceId, {
    id: adbDeviceId,
    source: 'adb',
    adbSerial: serial,
    info: {
      ip: `ADB:${serial}`,
      port: LOCAL_LOG_PORT,
      deviceName: `ADB: ${serial}`,
      connectionType: 'ADB'
    },
    lastSeen: Date.now(),
    type: 'adb',
    localPort: LOCAL_LOG_PORT
  });

  broadcastToClients({
    type: 'device_connected',
    device: { id: adbDeviceId, serial, source: 'adb', port: LOCAL_LOG_PORT }
  });
  broadcastDeviceList();

  return { started: true, alreadyRunning: false, sessionId, adbDeviceId };
}

// 判断一个 adb serial 是否是我们正在找的配对设备
// 支持：
//   A) serial 包含目标 IP（老机型 / IP 模式）
//   B) serial 以已知 guid 开头（Android 12+ mDNS 模式：guid._adb-tls-connect._tcp）
function isTargetSerial(serial, ip, guid) {
  if (!serial) return false;
  if (ip && serial.includes(ip)) return true;
  if (guid && serial.startsWith(guid)) return true;
  if (ip && (serial.startsWith(ip + ':') || serial === ip)) return true;
  return false;
}


function findAdbPath() {
  const adbPaths = [];
  
  // Electron 打包后，extraResources 会放在 process.resourcesPath 中
  if (process.resourcesPath) {
    adbPaths.push(path.join(process.resourcesPath, 'platform-tools', 'adb.exe'));
  }
  
  // 优先查找项目内置的 ADB（开发模式）
  const bundledAdb = path.join(__dirname, 'platform-tools', 'adb.exe');
  adbPaths.push(bundledAdb);
  
  if (process.env.ANDROID_HOME) {
    adbPaths.push(path.join(process.env.ANDROID_HOME, 'platform-tools', 'adb.exe'));
  }
  if (process.env.ANDROID_SDK_ROOT) {
    adbPaths.push(path.join(process.env.ANDROID_SDK_ROOT, 'platform-tools', 'adb.exe'));
  }
  
  const commonPaths = [
    'adb',
    path.join(process.env.LOCALAPPDATA || '', 'Android', 'Sdk', 'platform-tools', 'adb.exe'),
    path.join(process.env.USERPROFILE || '', 'AppData', 'Local', 'Android', 'Sdk', 'platform-tools', 'adb.exe'),
    path.join(process.env.USERPROFILE || '', 'Android', 'platform-tools', 'adb.exe'),
    path.join(process.env.USERPROFILE || '', 'Android', 'Sdk', 'platform-tools', 'adb.exe'),
  ];
  adbPaths.push(...commonPaths);
  
  for (const p of adbPaths) {
    try {
      fs.accessSync(p, fs.constants.F_OK);
      return p;
    } catch (e) {}
  }
  return 'adb';
}

let ADB_PATH = findAdbPath();

app.get('/api/adb/check', (req, res) => {
  exec(`"${ADB_PATH}" version`, (error, stdout, stderr) => {
    if (error) {
      res.json({ 
        available: false, 
        error: 'ADB not found', 
        path: ADB_PATH,
        hint: 'Please install Android SDK Platform Tools and add to PATH'
      });
    } else {
      res.json({ 
        available: true, 
        version: stdout.trim(), 
        path: ADB_PATH 
      });
    }
  });
});

app.get('/api/adb/devices', (req, res) => {
  const autoStart = (req.query.autoStart || '').toString().toLowerCase() === 'true';
  exec(`"${ADB_PATH}" devices -l`, (error, stdout) => {
    if (error) {
      res.json({ success: false, error: error.message, devices: [] });
      return;
    }
    // 解析所有状态（包括 offline / authorizing）：
    //  - 返回给前端完整状态，前端再自行判断是否能连接
    //  - autoStart=true 时，对所有 state === 'device' 且还没 session 的 serial 立即启动 logcat
    const parsedAll = parseAdbDevices(stdout);
    const responseDevices = [];
    for (const d of parsedAll) {
      responseDevices.push(d);
      if (autoStart && d.state === 'device') {
        try { ensureLogcatStartedForSerial(d.serial); } catch (e) {
          console.warn(`[ADB] auto-start logcat failed for ${d.serial}: ${e.message}`);
        }
      }
    }
    res.json({ success: true, devices: responseDevices });
  });
});

app.post('/api/adb/connect', async (req, res) => {
  const { serial, autoStart = true } = req.body;
  
  if (!serial) {
    return res.status(400).json({ success: false, error: 'Device serial required' });
  }
  
  try {
    // 先确认这个 serial 真的在 adb devices 列表里且状态正常，避免为 ghost serial 开 session
    let existingDevicePort = null;
    try {
      const confirmOut = await new Promise((resolve, reject) => {
        exec(`"${ADB_PATH}" devices -l`, { timeout: 4000 }, (e, o) => e ? reject(e) : resolve(o));
      });
      const parsed = parseAdbDevices(confirmOut);
      const found = parsed.find(d => d.serial === serial);
      if (!found) {
        return res.status(410).json({ success: false, error: `设备 ${serial} 不在 adb devices 列表中，请先配对或重新扫描` });
      }
      if (found.state !== 'device') {
        return res.status(409).json({ success: false, error: `设备状态异常 (${found.state})，请等待授权完成或重新连接` });
      }
    } catch (_) {}

    // 幂等：已在 adbSessions 中有活跃 session 的 serial，直接返回（避免重复开 logcat）
    for (const [sId, s] of adbSessions) {
      if (s.serial === serial && s.connected && !s.manualDisconnect) {
        const d = devices.get(s.id);
        const port = d && d.localPort ? d.localPort : null;
        console.log(`[ADB] /api/adb/connect: ${serial} already has active session ${sId}, reusing`);
        return res.json({
          success: true,
          deviceId: s.id,
          sessionId: sId,
          reused: true,
          port: port || 19527,
          message: `ADB 已连接 (复用已有会话) ${serial}`
        });
      }
    }

    if (!autoStart) {
      // 少数情况下前端只想要「连接登记」不要启动 logcat，这种我们也允许（空操作）
      const adbDeviceId = `adb_device_${serial.replace(/[^a-zA-Z0-9]/g, '_')}`;
      return res.json({
        success: true,
        deviceId: adbDeviceId,
        sessionId: null,
        message: `已登记 ADB 设备 ${serial} (autoStart=false)`
      });
    }

    // 真正创建 session + 启动 logcat
    const started = ensureLogcatStartedForSerial(serial);
    if (!started.started && !started.alreadyRunning) {
      return res.status(500).json({ success: false, error: `启动日志抓取失败 (serial=${serial})` });
    }

    const adbDeviceId = started.adbDeviceId || `adb_device_${serial.replace(/[^a-zA-Z0-9]/g, '_')}`;
    const sessionId = started.sessionId;
    const d = devices.get(adbDeviceId);
    const localPort = d && d.localPort ? d.localPort : (started.alreadyRunning ? 19527 : 19527);

    res.json({
      success: true,
      deviceId: adbDeviceId,
      sessionId,
      port: localPort,
      reused: started.alreadyRunning === true,
      message: started.alreadyRunning
        ? `ADB 已连接: ${serial} (复用已有日志抓取)`
        : `ADB connected to ${serial} (port forwarding: localhost:${localPort} -> device:9527)`
    });
    
  } catch (error) {
    res.status(500).json({ success: false, error: error.message });
  }
});

function connectToLogServerOrFallback(adb, serial, adbDeviceId, sessionId, localPort) {
  // 尝试通过 HTTP 连接 LogServer 的健康检查接口
  const options = {
    hostname: 'localhost',
    port: localPort,
    path: '/health',
    method: 'GET',
    timeout: 3000
  };
  
  const req = http.request(options, (res) => {
    let data = '';
    res.on('data', (chunk) => { data += chunk; });
    res.on('end', () => {
      try {
        const health = JSON.parse(data);
        if (health.status === 'ok') {
          console.log(`[ADB] LogServer reachable on ${serial}, using HTTP polling mode`);
          startHttpPolling(adb, serial, adbDeviceId, sessionId, localPort);
        } else {
          throw new Error('Unexpected health response');
        }
      } catch (e) {
        console.log(`[ADB] LogServer health check failed, falling back to logcat on ${serial}`);
        getAppPidAndStartLogcat(adb, serial, adbDeviceId, sessionId);
      }
    });
  });
  
  req.on('error', () => {
    console.log(`[ADB] Cannot reach LogServer on ${serial}, falling back to logcat`);
    getAppPidAndStartLogcat(adb, serial, adbDeviceId, sessionId);
  });
  
  req.on('timeout', () => {
    req.destroy();
    console.log(`[ADB] LogServer connection timeout, falling back to logcat`);
    getAppPidAndStartLogcat(adb, serial, adbDeviceId, sessionId);
  });
  
  req.end();
}

// 使用 HTTP 轮询方式获取日志（比 WebSocket 更稳定）
function startHttpPolling(adb, serial, adbDeviceId, sessionId, localPort) {
  const device = devices.get(adbDeviceId);
  if (device) {
    device.connected = true;
    device.wsMode = false;
    device.pollingMode = true;
  }
  broadcastDeviceList();
  
  let lastLogCount = 0;
  
  // 先获取已有日志
  fetchLogsOnce(localPort, adbDeviceId, lastLogCount, (newCount) => {
    lastLogCount = newCount || 0;
    
    // 然后定时轮询获取新日志
    const pollInterval = setInterval(() => {
      const session = adbSessions.get(sessionId);
      if (!session || !session.polling) {
        clearInterval(pollInterval);
        return;
      }
      fetchLogsOnce(localPort, adbDeviceId, lastLogCount, (newCount) => {
        if (newCount !== undefined) {
          lastLogCount = newCount;
        }
      });
    }, 1000); // 每秒轮询一次
    
    const sessionInfo = {
      id: adbDeviceId,
      sessionId,
      serial,
      connected: true,
      lastSeen: Date.now(),
      source: 'adb',
      mode: 'http_polling',
      localPort,
      polling: true,
      pollInterval
    };
    
    adbSessions.set(sessionId, sessionInfo);
    console.log(`[ADB] HTTP polling started for ${serial} (port: ${localPort})`);
  });
}

function fetchLogsOnce(localPort, adbDeviceId, lastCount, callback) {
  const options = {
    hostname: 'localhost',
    port: localPort,
    path: '/api/logs',
    method: 'GET',
    timeout: 2000
  };
  
  const req = http.request(options, (res) => {
    let data = '';
    res.on('data', (chunk) => { data += chunk; });
    res.on('end', () => {
      try {
        const result = JSON.parse(data);
        if (result && result.logs && Array.isArray(result.logs)) {
          const allLogs = result.logs;
          const newLogs = allLogs.slice(lastCount);
          
          for (const logItem of newLogs) {
            // 处理结构化日志数据
            const logEntry = {
              logType: logItem.type || 'info',
              deviceId: adbDeviceId,
              message: logItem.message || '',
              tag: logItem.tag || '',
              timestamp: logItem.timestamp || Date.now(),
              serverTime: new Date().toISOString()
            };
            
            // 添加到日志缓冲区
            logs.push(logEntry);
            if (logs.length > MAX_LOGS) {
              logs.shift();
            }
            
            // 推送到客户端
            broadcastToClients({
              type: 'log',
              log: logEntry
            });
          }
          
          if (callback) callback(allLogs.length);
        } else {
          if (callback) callback(lastCount);
        }
      } catch (e) {
        if (callback) callback(lastCount);
      }
    });
  });
  
  req.on('error', () => {
    if (callback) callback(lastCount);
  });
  
  req.on('timeout', () => {
    req.destroy();
    if (callback) callback(lastCount);
  });
  
  req.end();
}

function getAppPidAndStartLogcat(adb, serial, adbDeviceId, sessionId) {
  console.log(`[ADB] Getting PID for ${TV_LIVE_PACKAGE} on ${serial}...`);
  
  exec(`${adb} shell pidof -s ${TV_LIVE_PACKAGE}`, (err, stdout) => {
    if (err) {
      console.log(`[ADB] Failed to get PID: ${err.message}, falling back to tag-based`);
      startLogcatByTags(adb, serial, adbDeviceId, sessionId);
      return;
    }
    
    const pid = stdout.trim();
    
    if (pid && !isNaN(parseInt(pid))) {
      console.log(`[ADB] Found TV Live PID: ${pid} on ${serial}, starting logcat with PID filter`);
      startLogcatByPid(adb, serial, adbDeviceId, sessionId, pid);
    } else {
      console.log(`[ADB] PID not found (app not running), using all-tags logcat on ${serial}`);
      startLogcatByTags(adb, serial, adbDeviceId, sessionId);
    }
  });
}

function startLogcatByPid(adb, serial, adbDeviceId, sessionId, pid) {
  console.log(`[ADB] Starting logcat with PID filter: ${pid}`);
  const adbArgs = ['-s', serial, 'logcat', '-v', 'time', `--pid=${pid}`];
  spawnLogcatProcess(adbArgs, adb, serial, adbDeviceId, sessionId, 'pid');
}

function startLogcatByTags(adb, serial, adbDeviceId, sessionId) {
  console.log(`[ADB] Starting logcat with all tags`);
  // 不过滤标签，抓取所有日志
  const adbArgs = ['-s', serial, 'logcat', '-v', 'time'];
  spawnLogcatProcess(adbArgs, adb, serial, adbDeviceId, sessionId, 'all');
}

function spawnLogcatProcess(adbArgs, adb, serial, adbDeviceId, sessionId, mode) {
  const logcatProcess = spawn(ADB_PATH, adbArgs, {
    shell: false,
    stdio: ['ignore', 'pipe', 'pipe']
  });
  
  const sessionInfo = {
    id: adbDeviceId,
    sessionId,
    serial,
    process: logcatProcess,
    connected: true,
    lastSeen: Date.now(),
    source: 'adb',
    mode,
    reconnectAttempts: 0,
    maxReconnectAttempts: 5
  };
  
  adbSessions.set(sessionId, sessionInfo);
  
  logcatProcess.stdout.on('data', (data) => {
    const lines = data.toString().split('\n');
    for (const line of lines) {
      if (line.trim()) {
        parseAndPushAdbLog(adbDeviceId, line, mode);
      }
    }
  });
  
  logcatProcess.stderr.on('data', (data) => {
    console.error(`[ADB ${serial}] stderr:`, data.toString().substring(0, 200));
  });
  
  logcatProcess.on('close', (code) => {
    console.log(`[ADB ${serial}] logcat exited with code ${code}`);
    
    // 检查是否应该重连
    const currentSession = adbSessions.get(sessionId);
    
    // 如果 session 不存在或已标记为手动断开，不重连
    if (!currentSession || currentSession.manualDisconnect) {
      console.log(`[ADB ${serial}] Session removed or manually disconnected, not reconnecting`);
      if (currentSession && currentSession.manualDisconnect) {
        // 手动断开，清理设备
        adbSessions.delete(sessionId);
        const device = devices.get(adbDeviceId);
        if (device) {
          broadcastToClients({ type: 'device_disconnected', deviceId: adbDeviceId });
          devices.delete(adbDeviceId);
          broadcastDeviceList();
        }
      }
      return;
    }
    
    if (currentSession.reconnectAttempts < currentSession.maxReconnectAttempts) {
      console.log(`[ADB ${serial}] Attempting reconnect (${currentSession.reconnectAttempts + 1}/${currentSession.maxReconnectAttempts})...`);
      
      currentSession.reconnectAttempts++;
      
      // 检查设备是否仍然连接
      exec(`"${ADB_PATH}" devices`, (err, stdout) => {
        if (stdout.includes(serial)) {
          // 设备仍连接，重启logcat
          console.log(`[ADB ${serial}] Device still connected, restarting logcat...`);
          setTimeout(() => {
            // 再次检查 session 是否存在
            const session = adbSessions.get(sessionId);
            if (session && !session.manualDisconnect) {
              const newSessionInfo = {
                id: adbDeviceId,
                sessionId,
                serial,
                process: null,
                connected: true,
                lastSeen: Date.now(),
                source: 'adb',
                mode,
                reconnectAttempts: currentSession.reconnectAttempts,
                maxReconnectAttempts: currentSession.maxReconnectAttempts
              };
              adbSessions.set(sessionId, newSessionInfo);
              startLogcatByTags(adb, serial, adbDeviceId, sessionId);
            }
          }, 1000);
        } else {
          // 设备已断开
          console.log(`[ADB ${serial}] Device disconnected, removing session`);
          adbSessions.delete(sessionId);
          const device = devices.get(adbDeviceId);
          if (device) {
            broadcastToClients({ type: 'device_disconnected', deviceId: adbDeviceId });
            devices.delete(adbDeviceId);
            broadcastDeviceList();
          }
        }
      });
    } else {
      // 超过重连次数
      console.log(`[ADB ${serial}] Max reconnect attempts reached, removing session`);
      adbSessions.delete(sessionId);
      const device = devices.get(adbDeviceId);
      if (device) {
        broadcastToClients({ type: 'device_disconnected', deviceId: adbDeviceId });
        devices.delete(adbDeviceId);
        broadcastDeviceList();
      }
    }
  });
  
  logcatProcess.on('error', (err) => {
    console.error(`[ADB ${serial}] process error:`, err);
  });
  
  console.log(`[ADB ${serial}] Logcat started (mode: ${mode})`);
}

app.post('/api/adb/disconnect', (req, res) => {
  let { sessionId, deviceId } = req.body;
  
  console.log(`[ADB Disconnect] Request received: sessionId=${sessionId}, deviceId=${deviceId}`);
  
  // 支持通过 sessionId 或 deviceId 断开
  let session = null;
  let targetDeviceId = null;
  let targetSerial = null;
  
  if (sessionId && adbSessions.has(sessionId)) {
    session = adbSessions.get(sessionId);
    targetDeviceId = session.id;
    targetSerial = session.serial;
  } else if (deviceId) {
    // 通过 deviceId 查找对应的 session
    for (const [sId, s] of adbSessions) {
      if (s.id === deviceId) {
        session = s;
        targetDeviceId = deviceId;
        targetSerial = s.serial;
        sessionId = sId;
        break;
      }
    }
  }
  
  if (session) {
    console.log(`[ADB Disconnect] Found session for device ${targetDeviceId}`);
    
    // 标记为手动断开，防止重连
    session.manualDisconnect = true;
    
    // 清除 HTTP 轮询
    if (session.pollInterval) {
      clearInterval(session.pollInterval);
    }
    // 清除 logcat 进程
    if (session.process) {
      try {
        session.process.kill();
      } catch (e) {}
    }
    adbSessions.delete(sessionId);
    
    // 如果有 serial，执行 adb disconnect
    if (session.serial) {
      try {
        exec(`"${ADB_PATH}" disconnect ${session.serial}`, (err) => {
          if (err) {
            console.warn(`[ADB] Disconnect command failed: ${err.message}`);
          } else {
            console.log(`[ADB] Disconnected ${session.serial}`);
          }
        });
      } catch (e) {}
    }
    
    // 清除设备
    const device = devices.get(targetDeviceId);
    if (device) {
      broadcastToClients({ type: 'device_disconnected', deviceId: targetDeviceId });
      devices.delete(targetDeviceId);
      broadcastDeviceList();
    }
    
    res.json({ success: true, message: '设备已断开' });
  } else {
    // 即使找不到 session，也尝试删除设备和断开 ADB
    if (deviceId && devices.has(deviceId)) {
      const device = devices.get(deviceId);
      const serial = device.adbSerial;
      
      // 尝试执行 adb disconnect
      if (serial) {
        try {
          exec(`"${ADB_PATH}" disconnect ${serial}`, (err) => {
            if (err) {
              console.warn(`[ADB] Disconnect command failed: ${err.message}`);
            } else {
              console.log(`[ADB] Disconnected ${serial}`);
            }
          });
        } catch (e) {}
      }
      
      broadcastToClients({ type: 'device_disconnected', deviceId });
      devices.delete(deviceId);
      broadcastDeviceList();
      res.json({ success: true, warning: 'Session not found, device removed' });
    } else if (targetSerial) {
      // 有 serial 但找不到设备，直接断开 ADB
      try {
        exec(`"${ADB_PATH}" disconnect ${targetSerial}`, (err) => {
          if (err) {
            console.warn(`[ADB] Disconnect command failed: ${err.message}`);
          }
        });
      } catch (e) {}
      res.json({ success: true, message: 'ADB disconnected' });
    } else {
      res.status(404).json({ success: false, error: 'Session not found' });
    }
  }
});

// ADB 无线配对
app.post('/api/adb/pair', async (req, res) => {
  const { ip, port, pairingCode } = req.body;
  
  if (!ip || !port || !pairingCode) {
    return res.status(400).json({ success: false, error: 'IP、端口和配对码都不能为空' });
  }
  
  try {
    console.log(`[ADB] Pairing ${ip}:${port} with code ${pairingCode}`);
    
    // 使用 echo 管道方式将配对码传递给 adb pair
    // Windows 下使用 cmd /c 执行管道命令
    const pairCmd = `echo ${pairingCode}| "${ADB_PATH}" pair ${ip}:${port}`;
    
    exec(pairCmd, { timeout: 20000 }, (err, stdout, stderr) => {
      const output = (stdout || '') + (stderr || '');
      console.log(`[ADB pair] Output: ${output.trim()}`);
      
      if (err && err.killed) {
        return res.status(500).json({ success: false, error: '配对超时，请重试' });
      }
      
      if (output.includes('Successfully paired') || output.includes('already paired')) {
        // ---------- 配对成功后的自动连接策略 ----------
        // 已知实际情况：
        //   A) 部分机型配对端口本身 + 1~2 秒后即可 connect（OPPO/vivo）
        //   B) 部分机型连接端口 = 配对端口 ±3~10（MIUI/ColorOS 动态变化）
        //   C) 部分机型配对端口和连接端口相同（Android 原生）
        //   D) Android 11+ 有些配对后会直接出现在 adb devices 列表里
        //   E) 授权弹窗需要 2~10 秒才会跳出来点允许，所以必须「多次 + 延迟重试 adb devices」
        // -------------------------------------------------
        console.log(`[ADB] Pairing successful, trying to auto-connect to ${ip}...`);

        // 1) 从 pair 输出中提取 guid（可选，后续扩展 mDNS 用）
        let guid = null;
        const guidM = output.match(/\[guid=([^\]]+)\]/);
        if (guidM && guidM[1]) guid = guidM[1];
        console.log(`[ADB] Parsed pair guid=${guid || '(n/a)'}`);
        
        const pairPortNum = parseInt(String(port), 10);

        // 2) 构造候选端口列表：覆盖 A/B/C 三种情况（按命中率排序）
        const candidatePorts = [];
        const pushPort = (p) => {
          if (Number.isFinite(p) && p >= 1024 && p <= 65535) candidatePorts.push(p);
        };

        if (!Number.isNaN(pairPortNum)) {
          pushPort(pairPortNum);                 // C) 直接用配对端口本身（很多小米/华为就是这个）
          pushPort(pairPortNum + 1);             // A) ±1 最多
          pushPort(pairPortNum - 1);
          pushPort(pairPortNum + 2);             // ±2
          pushPort(pairPortNum - 2);
          pushPort(pairPortNum + 3);             // B) MIUI/ColorOS 偏移量更大
          pushPort(pairPortNum - 3);
          pushPort(pairPortNum + 5);
          pushPort(pairPortNum - 5);
          pushPort(pairPortNum + 10);
          pushPort(pairPortNum - 10);
        }
        // 标准 WiFi 调试端口
        [5555, 5556, 5554, 5557, 5553].forEach(pushPort);
        // Android 无线调试常用大端口区段（厂商自定义）
        [37000, 37500, 38000, 38500, 39000, 39500,
         40000, 40500, 41000, 41500, 42000, 42500, 43000, 43500, 44000].forEach(pushPort);

        // 去重，保持顺序（候选越多越要避免重复）
        const uniquePorts = Array.from(new Set(candidatePorts));
        const candidateEndpoints = uniquePorts.map(p => `${ip}:${p}`);
        // 最后再补一个「不带端口的 IP」（内部默认 5555）
        candidateEndpoints.push(`${ip}`);
        console.log(`[ADB] Candidate connect ports: ${uniquePorts.length} unique, endpoints total: ${candidateEndpoints.length}`);

        let bestSerial = null;
        let replied = false;
        // 当前候选已经跑完了多少个（index）
        let endpointIdx = 0;
        // 每个候选连接完之后，等授权的「多次 adb devices 重试轮次」
        let verifyAttempts = 0;
        const MAX_VERIFY_ATTEMPTS = 5; // 每个候选最大 5 次 devices 扫描（1s/次 = 最多 5 秒等待授权弹窗）
        const verifyEndpoint = null; // 当前在 verify 的 endpoint

        const sendAutoConnectFailed = () => {
          if (replied) return;
          replied = true;
          res.json({
            success: true,
            message: '配对成功，但自动连接失败。请点击"扫描ADB设备"手动连接',
            autoConnectFailed: true
          });
        };

        const claimSuccess = (serial) => {
          if (replied || !serial) return;
          replied = true;
          bestSerial = serial;
          console.log(`[ADB] Auto-connected successfully: ${serial}`);
          res.json({
            success: true,
            deviceId: serial,
            message: `配对成功，已自动连接 ${serial}，开始抓取日志...`
          });
          // suppressResponse=true 避免在 connectToDeviceAndStartLogging 里再发 res.json
          try {
            connectToDeviceAndStartLogging(serial, null, true);
          } catch (e) {
            console.warn(`[ADB] start logging error: ${e.message}`);
          }
        };

        // 扫描 adb devices -l，找匹配的设备
        // 匹配规则（任一命中即可）：
        //   A) serial 中包含目标 IP（旧机型 / IP 模式：192.168.1.14:37123）
        //   B) serial === IP，或 serial.startsWith(IP + ':')
        //   C) 若解析到 guid：serial.startsWith(guid)（Android 12+ mDNS：guid._adb-tls-connect._tcp）
        const scanForMatchedDevice = (callback) => {
          exec(`"${ADB_PATH}" devices -l`, { timeout: 4000 }, (scanErr, scanOut) => {
            if (scanErr) return callback(null);
            const parsed = parseAdbDevices(scanOut || '');
            for (const d of parsed) {
              if (d.state !== 'device') continue;
              if (isTargetSerial(d.serial, ip, guid)) {
                console.log(`[ADB] scan matched: serial=${d.serial} state=${d.state} (ip=${ip} guid=${guid || '-'})`);
                return callback(d.serial);
              }
            }
            return callback(null);
          });
        };

        // 阶段零：Android 12+ mDNS「专用」长轮询
        // 现象：配对成功后，手机立刻显示「已连接到无线调试」，
        //      但 `adb connect <ip>:<any port>` 全部报 10061（端口根本没开），
        //      此时 adb 内部已经通过 mDNS/TLS 建立通道，设备会在 ~1~10s 后
        //      以 `serial = <guid>._adb-tls-connect._tcp` 的形式出现在 devices 列表。
        // 这里给 guid 单独开一个高优先级长轮询，命中就直接成功，不走 TCP 端口扫描。
        if (guid) {
          console.log(`[ADB] Phase 0: long-polling devices for guid serial (${guid}...)`);
          let guidPolls = 0;
          const GUID_MAX_POLLS = 20; // 1s * 20 = 20 秒兜底
          const guidPollLoop = () => {
            if (replied) return;
            scanForMatchedDevice((serial) => {
              if (serial) {
                console.log(`[ADB] Phase 0 (guid poll) found device on attempt ${guidPolls + 1}/${GUID_MAX_POLLS}`);
                return claimSuccess(serial);
              }
              guidPolls++;
              if (guidPolls >= GUID_MAX_POLLS) {
                console.log(`[ADB] Phase 0 guid poll exhausted, falling through to TCP endpoints`);
                // 20 秒都没出现在列表里，再走阶段一/阶段二的传统逻辑
                setTimeout(doImmediateScan, 0);
                return;
              }
              setTimeout(guidPollLoop, 1000);
            });
          };
          // 配对刚结束就立刻开始 guid 轮询（mDNS 通道通常 1~5 秒内建立）
          guidPollLoop();
        } else {
          // 没解析到 guid：直接走传统「立即扫描 + IP 端口扫描」逻辑
          setTimeout(doImmediateScan, 1200);
        }

        // 阶段一：配对成功后立刻连扫 3 次 devices（D 场景：
        // 部分机型「已连接」不需要再 adb connect）
        let immediatePasses = 0;
        const doImmediateScan = () => {
          if (replied) return;
          scanForMatchedDevice((serial) => {
            if (serial) return claimSuccess(serial);
            immediatePasses++;
            if (immediatePasses < 3) {
              setTimeout(doImmediateScan, 1000);
            } else {
              // 阶段二：逐个候选端口连接 + 验证
              console.log(`[ADB] Immediate scan didn't find a ready device, starting endpoint walk...`);
              tryNextEndpoint();
            }
          });
        };

        // 单个 endpoint 的验证过程：最多 5 次 devices 扫描（等待授权）
        const verifyCurrentEndpoint = () => {
          if (replied || bestSerial) return;
          scanForMatchedDevice((serial) => {
            if (serial) return claimSuccess(serial);
            verifyAttempts++;
            if (verifyAttempts >= MAX_VERIFY_ATTEMPTS) {
              // 这个 endpoint 失败了，下一个
              verifyAttempts = 0;
              tryNextEndpoint();
            } else {
              setTimeout(verifyCurrentEndpoint, 1000);
            }
          });
        };

        const tryNextEndpoint = () => {
          if (replied || bestSerial) return;
          if (endpointIdx >= candidateEndpoints.length) {
            // 全部失败：最终兜底长轮询（带 guid 时再轮 15 次，没有 guid 时轮 8 次）
            console.warn(`[ADB] All ${candidateEndpoints.length} endpoints failed; doing final last-resort poll (guid=${guid ? 'yes' : 'no'})...`);
            let polls = 0;
            const MAX_FINAL_POLLS = guid ? 15 : 8;
            const pollLoop = () => {
              if (replied) return;
              scanForMatchedDevice((serial) => {
                if (serial) return claimSuccess(serial);
                polls++;
                if (polls >= MAX_FINAL_POLLS) return sendAutoConnectFailed();
                setTimeout(pollLoop, 1000);
              });
            };
            pollLoop();
            return;
          }

          const endpoint = candidateEndpoints[endpointIdx++];
          console.log(`[ADB] Connect attempt ${endpointIdx}/${candidateEndpoints.length}: ${endpoint}`);

          exec(`"${ADB_PATH}" connect ${endpoint}`, { timeout: 5000 }, (connErr, connOut, connErr2) => {
            if (replied || bestSerial) return;
            const connResult = ((connOut || '') + (connErr2 || '')).trim();
            console.log(`[ADB connect ${endpoint}] => ${connResult || '(empty)'}`);

            const clearlyFailed = /failed|cannot|unable|refused|no route|cannot connect/i.test(connResult);
            if (clearlyFailed) {
              // 明确端口未开放，立刻试下一个候选
              setImmediate(tryNextEndpoint);
              return;
            }
            // other cases: connected / already connected / authorizing / (empty)
            // 开始多次 verify：每 1 秒扫描一次 devices，最多等 5 秒（授权弹窗时间）
            verifyAttempts = 0;
            setTimeout(verifyCurrentEndpoint, 1000);
          });
        };

      } else if (output.includes('Invalid pairing code') || output.includes('Bad pairing code')) {
        res.json({ success: false, error: '配对码无效，请检查后重试' });
      } else if (output.includes('No devices') || output.includes('cannot connect')) {
        res.json({ success: false, error: '无法连接到设备，请确认 IP 和端口正确，且手机在无线调试设置中显示配对对话框' });
      } else {
        res.json({ success: false, error: `配对失败: ${output.trim() || '未知错误'}` });
      }
    });
    
  } catch (error) {
    res.status(500).json({ success: false, error: error.message });
  }
});

// ADB 无线连接
app.post('/api/adb/wireless-connect', async (req, res) => {
  const { ip, port } = req.body;
  
  if (!ip || !port) {
    return res.status(400).json({ success: false, error: 'IP 和端口不能为空' });
  }
  
  try {
    console.log(`[ADB] Connecting wirelessly to ${ip}:${port}`);
    
    exec(`"${ADB_PATH}" connect ${ip}:${port}`, (err, stdout, stderr) => {
      const output = (stdout || '') + (stderr || '');
      console.log(`[ADB connect] ${output.trim()}`);
      
      if (output.includes('connected')) {
        // 连接成功后获取设备列表
        setTimeout(() => {
          exec(`"${ADB_PATH}" devices -l`, (err2, stdout2) => {
            const lines = stdout2.trim().split('\n');
            const devices = [];
            
            for (let i = 1; i < lines.length; i++) {
              const line = lines[i].trim();
              if (!line) continue;
              
              const parts = line.split(/\s+/);
              if (parts.length >= 2 && parts[1] === 'device') {
                // 发现设备后自动连接抓取日志
                const serial = parts[0];
                if (serial.includes(ip)) {
                  // 调用连接 API
                  connectToDeviceAndStartLogging(serial, res);
                  return;
                }
              }
            }
            
            res.json({ success: true, message: '连接成功', devices: [] });
          });
        }, 500);
      } else if (output.includes('failed') || output.includes('unable')) {
        res.json({ success: false, error: `连接失败: ${output.trim()}` });
      } else {
        res.json({ success: false, error: `连接失败: ${output.trim()}` });
      }
    });
  } catch (error) {
    res.status(500).json({ success: false, error: error.message });
  }
});

// 直接连接设备并开始抓取日志
// suppressResponse: true 时跳过 res.json 响应（调用方自己已返回过响应，例如 pair API）
function connectToDeviceAndStartLogging(serial, res, suppressResponse) {
  const sessionId = `adb_${Date.now()}_${Math.random().toString(36).substr(2, 6)}`;
  const adbDeviceId = `adb_device_${serial.replace(/[^a-zA-Z0-9]/g, '_')}`;
  const adb = `"${ADB_PATH}" -s "${serial}"`;
  
  console.log(`[ADB] Connecting to device ${serial}, creating session ${sessionId}`);
  
  const startLogcat = () => {
    console.log(`[ADB] Starting logcat for ${serial}...`);
    setTimeout(() => {
      try {
        getAppPidAndStartLogcat(adb, serial, adbDeviceId, sessionId);
      } catch (e) {
        console.error(`[ADB] Failed to start logcat for ${serial}:`, e);
        // 失败后尝试直接用标签模式
        startLogcatByTags(adb, serial, adbDeviceId, sessionId);
      }
    }, 1500);
  };
  
  // 尝试启动应用
  exec(`${adb} shell pm list packages | findstr "${TV_LIVE_PACKAGE}"`, (err, stdout) => {
    const appInstalled = !err && stdout.includes(TV_LIVE_PACKAGE);
    
    if (appInstalled) {
      console.log(`[ADB] TV Live installed on ${serial}, starting app...`);
      exec(`${adb} shell am force-stop ${TV_LIVE_PACKAGE}`, () => {
        exec(`${adb} shell am start -n ${TV_LIVE_PACKAGE}/.MainActivity`, (startErr) => {
          if (startErr) {
            console.warn(`[ADB] Failed to start TV Live: ${startErr.message}`);
          } else {
            console.log(`[ADB] TV Live started on ${serial}`);
          }
        });
      });
    } else {
      console.log(`[ADB] TV Live not installed on ${serial}, starting logcat anyway`);
    }
    
    startLogcat();
  });
  
  devices.set(adbDeviceId, {
    id: adbDeviceId,
    source: 'adb',
    adbSerial: serial,
    info: { 
      ip: `ADB:${serial}`, 
      port: 0,
      deviceName: `ADB: ${serial}`,
      connectionType: 'ADB'
    },
    lastSeen: Date.now(),
    type: 'adb',
    connected: true
  });
  
  broadcastToClients({
    type: 'device_connected',
    device: { id: adbDeviceId, serial, source: 'adb' }
  });
  broadcastDeviceList();
  
  if (!suppressResponse && res && typeof res.json === 'function') {
    res.json({ 
      success: true, 
      deviceId: adbDeviceId, 
      sessionId,
      message: `已连接到 ${serial}` 
    });
  }
}

// 应用相关标签列表（精简版：仅 TV Live 自有标签 + 应用内使用的第三方库）
// 注意：系统服务标签（ActivityManager / WifiManager / PowerManager 等）一律不要放进来，
//       这些 tag 任何进程都能写，无法区分是否来自 TVLive 进程
const APP_TAGS = [
  // ===== TV Live 应用核心自有标签 =====
  'TVLive', 'TVPlayerManager', 'LiveSourceLoader',
  'HuyaSDKLogger', 'HuyaSDKParser', 'HuyaStreamPlayer', 'HuyaCacheGov', 'HYC',
  'BootReceiver', 'BootStartManager', 'BootStartReceiver', 'BootJobService',
  'BootStartFgService', 'BootStartForegroundService',
  'SourceHealthChecker', 'SourceDialogManager',
  'SecChk', 'RedirectHttp', 'DecoderModeManager', 'DeviceCapabilities',
  'VariantManager', 'AppCacheInspector',
  'MyApplication', 'SecurityCore', 'TVLS', 'MainActivity', 'KEY_DEBUG',
  'tv.live', 'com.tv.live',
  // ===== TV Live 公用基础标签 =====
  'CrashHandler', 'LogServer',
  // ===== 应用中使用的第三方库/播放器/网络 =====
  'ExoPlayer', 'IjkMediaPlayer',
  'OkHttp', 'OkHttpClient', 'Retrofit',
  'MediaPlayer', 'AndroidMediaPlayer', 'SoftwareMediaPlayer', 'MediaCodec',
  'AndroidRuntime', 'RuntimeException',
  'Gson', 'Glide', 'Coil',
  'Coroutine', 'Kotlin',
  'Firebase', 'Bugly', 'Sentry',
  'WebView', 'Chromium',
  // ===== 应用内 System.out/err/DEBUG =====
  'DEBUG', 'System.err', 'System.out'
];

// ===== 虎牙 Berry SDK 识别规则（命中任意一条即标记 isHuyaSdk=true） =====
// 注意：isHuyaSdk 只是分类，不覆盖原有的 error/crash/warn/info/debug 级别，
//       这样虎牙 SDK 内部崩溃依然会显示为红色崩溃徽章。

// 规则 1: tag 精确匹配（SDK 内部日志 tag / 项目封装 logger tag）
const HUYA_TAGS = [
  // 项目封装类 tag（APP_TAGS 里已有，但这里单独列作为虎牙分类依据）
  'HuyaSDKLogger', 'HuyaSDKParser', 'HuyaStreamPlayer', 'HuyaCacheGov', 'HYC',
  // 虎牙 Berry SDK 内部 tag（反编译和运行时常见）
  'HuyaBerry', 'HuyaBerryImpl', 'BerrySDK', 'HYLiveSDK', 'HYLive',
  'BerryEvent', 'CustomUICallback', 'HuyaPlayer',
  // 内部组件
  'PlayerView', 'PresenterConfigHelper', 'SdkProperties', 'SPHelper',
  // 工具类（duowan/auk 包属于虎牙早期 Berry SDK 依赖）
  'ArkToast', 'Config'
];

// 规则 2: message 中包含的关键词 / 类名 / API 方法名（不区分大小写匹配）
const HUYA_KEYWORDS = [
  // 包名前缀
  'com.huya.berry', 'com.duowan.auk', 'com.huya.live',
  // 入口 & 配置类
  'HuyaBerry', 'HuyaBerryConfig', 'StartLiveConfig', 'HuyaBerryPlayConfig',
  'HYLiveSDK', 'initHYLiveSDK', 'openHYLiveSDK', 'exitHYLiveSDK', 'changeLandscapeHYLiveSDK',
  // Berry 实例 API
  '.instance()', 'setPlayConfig(', 'startLive(', 'pauseLive(', 'watchLive(', 'watchLiveByUid(',
  'fullScreenPlay(', 'smallWindowPlay(', 'pauseVideoPlay(', 'startVideoPlay(',
  'switchDanmu(', 'switchVoice(', 'closeFloat(', 'changeLandscapeMode(',
  'setReceiveDanmuData(', 'showDanmuView(', 'hideDanmuView(', 'sendDanmu(',
  'getLiveListData(', 'getTagListData(', 'getLiveListDataByTag(', 'getLiveData(', 'getLiveDataByRoomId(',
  'customUIStartLive(', 'customUIGetAuthorInfo(', 'customUIModifyNickname(',
  'customUIModifyTitle(', 'customUIModifyAnnouncement(',
  'customUILogin(', 'customUILogout(',
  'customUIGetResolution(', 'customUISetResolution(',
  'customUIOpenQuality(', 'customUIOpenSendDanmu(',
  'subscribe(', 'unSubscribe(', 'querySubscribeStatus(',
  'setBerryEventDelegate(', 'sendPlayerData(', 'sendGameUpData(',
  'setGameAccountID(', 'setGangUpTip(',
  'changeGame(', 'joinChannel(', 'rtmpPushLive(', 'stopRtmpLive(',
  'uninit(', 'onActivityResult(',
  // 数据模型类
  'LiveInfo', 'LiveListInfo', 'BitRateInfo', 'AuthorInfo', 'SubscribeInfo',
  'OptionalResolution', 'ErrorInfo', 'RtmpPushInfo', 'BaseCallback',
  'BerryPlayerDataHelper', 'getPlayUrlByLineAndBitrate', 'getLines(', 'getBitRateList(',
  // 回调接口
  'CustomUICallback', 'onResultCallback(', 'onResultListCallback(',
  'onEventCallback(',
  // BerryEvent 事件常量 / 字段
  'BERRYEVENT_EVENTTYPE', 'BERRYEVENT_RESULTCODE', 'BERRYEVENT_RESULTMSG',
  'BERRYEVENT_ROOMID', 'BERRYEVENT_UID', 'BERRYEVENT_STARTUPTIME',
  'BERRYEVENT_STARTLIVETIME', 'BERRYEVENT_ENDLIVETIME', 'BERRYEVENT_DURATION',
  'BERRYEVENT_RECEIVEDANMU', 'BERRYEVENT_DANMUCONTENT', 'BERRYEVENT_NICKNAME',
  'BERRYEVENT_ATTENDANCECOUNT', 'BERRYEVENT_LIVEID', 'BERRYEVENT_ISLAST',
  'BERRYEVENT_GAMEACCOUNTID',
  'EVENTTYPE_INIT', 'EVENTTYPE_STARTUP', 'EVENTTYPE_STARTLIVE',
  'EVENTTYPE_RESTARTLIVE', 'EVENTTYPE_ENDLIVE', 'EVENTTYPE_SENDPLAYERDATA',
  'EVENTTYPE_EXITFULLSCREEN', 'EVENTTYPE_FULLSCREEN',
  'EVENTTYPE_CLOSELIVELIST', 'EVENTTYPE_SHOWFLOATING',
  // 常见回调结果
  'BaseCallback.SUCCESS', 'RESULTCODE_FAIL', 'RESULTCODE_SUCCESS',
  'errorMsg',
  // 虎牙 SDK 内部网络/播放器前缀
  'HuyaHttp', 'HuyaSocket', 'HuyaStream', 'HuyaMedia', 'HuyaDecoder',
  // 虎牙 SDK 异常
  'HuyaException', 'BerryException', 'HuyaError', 'SDK init fail',
  'init failed', 'SDK not init'
];

// 规则 3: 正则匹配（更灵活的 message 模式，如类栈、方法签名等）
const HUYA_PATTERNS = [
  /com\.huya\.berry\.[A-Za-z0-9_$]+/,            // com.huya.berry.* 任意类名
  /at com\.huya\./,                                  // 崩溃堆栈：at com.huya.xxx
  /at com\.duowan\.auk\./,                           // 崩溃堆栈：at com.duowan.auk.xxx
  /Caused by: com\.huya\./,
  /HuyaBerryConfig\.Builder/,
  /StartLiveConfig\.Builder/,
  /HuyaBerryPlayConfig\.Builder/,
  /CustomUICallback.*code\s*[=:]\s*-?\d+/,           // CustomUICallback code=xxx
  /resultCode\s*[=:]\s*["']?-?\d+["']?/i,            // BERRYEVENT_RESULTCODE 值
  /roomId\s*[=:]\s*\d+/i,                             // roomId=12345 字段
  /Huya(Berry|SDK|Live|Stream)/i                      // 常见组合词（大小写不敏感）
];

// 判断是否为虎牙 SDK 日志
function isHuyaSdkLog(tag, message, logLine) {
  if (!tag && !message && !logLine) return false;
  const t = tag || '';
  const m = message || '';
  const ll = logLine || '';
  // 1) tag 命中
  if (t && HUYA_TAGS.some(h => t.includes(h))) return true;
  // 2) message 关键词命中
  if (m) {
    for (const kw of HUYA_KEYWORDS) {
      if (m.includes(kw)) return true;
    }
  }
  // 3) 正则命中（message 或整行）
  for (const re of HUYA_PATTERNS) {
    if (re.test(m) || re.test(ll) || re.test(t)) return true;
  }
  return false;
}

// 统计日志数量
let totalParsedLogs = 0;

function parseAndPushAdbLog(deviceId, line, mode) {
  try {
    totalParsedLogs++;
    
    const logEntry = {
      type: 'log',
      timestamp: Date.now(),
      tag: '',
      message: line.trim(),
      logType: 'info',
      deviceId: deviceId,
      deviceName: 'ADB Device',
      deviceModel: '',
      appVersion: ''
    };
    
    const logLine = line.trim();
    
    // 解析 logcat 输出格式
    // 格式1: MM-DD HH:MM:SS.mmm PID:TID LEVEL/TAG: message
    // 格式2: MM-DD HH:MM:SS.mmm PID PID LEVEL TAG: message
    // 格式3: MM-DD HH:MM:SS.mmm LEVEL/TAG: message (PID信息合并)
    
    const timeMatch = logLine.match(/^(\d{2})-(\d{2})\s+(\d{2}):(\d{2}):(\d{2})\.(\d{3})/);
    if (timeMatch) {
      const now = new Date();
      const year = now.getFullYear();
      const month = parseInt(timeMatch[1], 10) - 1;
      const day = parseInt(timeMatch[2], 10);
      const hour = parseInt(timeMatch[3], 10);
      const minute = parseInt(timeMatch[4], 10);
      const second = parseInt(timeMatch[5], 10);
      const millis = parseInt(timeMatch[6], 10);
      const parsedDate = new Date(year, month, day, hour, minute, second, millis);
      
      // 处理跨年：如果解析的日期比当前时间晚超过1个月，说明是去年
      if (parsedDate.getTime() - now.getTime() > 30 * 24 * 3600 * 1000) {
        parsedDate.setFullYear(year - 1);
      }
      
      logEntry.timestamp = parsedDate.getTime();
    }
    
    let rest = timeMatch ? logLine.substring(timeMatch[0].length).trim() : logLine;
    
    let level = '';
    let tag = '';
    let message = logLine;
    
    // 尝试多种格式匹配
    // 格式1: LEVEL/TAG: message (最常见)
    let match = rest.match(/^([VDIWEF])\/([\w\-\.]+):\s*(.*)/);
    if (match) {
      level = match[1];
      tag = match[2];
      message = match[3];
    }
    
    // 格式2: PID PID LEVEL TAG: message
    if (!match) {
      match = rest.match(/^\d+\s+\d+\s+([VDIWEF])\s+([\w\-\.]+):\s*(.*)/);
      if (match) {
        level = match[1];
        tag = match[2];
        message = match[3];
      }
    }
    
    // 格式3: PID:TID LEVEL/TAG: message
    if (!match) {
      match = rest.match(/^\d+:\d+\s+([VDIWEF])\/([\w\-\.]+):\s*(.*)/);
      if (match) {
        level = match[1];
        tag = match[2];
        message = match[3];
      }
    }
    
    // 格式4: 只有 LEVEL/TAG: message (无时间和PID)
    if (!match) {
      match = rest.match(/^([VDIWEF])\/([\w\-\.]+):\s*(.*)/);
      if (match) {
        level = match[1];
        tag = match[2];
        message = match[3];
      }
    }
    
    // 格式5: 只有 TAG: message (无级别)
    if (!match) {
      match = rest.match(/^([\w\-\.]+):\s*(.*)/);
      if (match) {
        tag = match[1];
        message = match[2];
        level = 'I'; // 默认信息级别
      }
    }
    
    logEntry.tag = tag || 'Unknown';
    logEntry.message = message || logLine;
    
    // 标记是否是应用相关日志
    // 1) 如果 logcat 是按 TVLive PID 过滤启动的（mode='pid'），所有日志天然就是应用日志
    // 2) 否则按 APP_TAGS 精确匹配 tag，并辅以 message 中包含包名的弱提示
    let isAppLog;
    if (mode === 'pid') {
      isAppLog = true;
    } else {
      const tagHit = tag && APP_TAGS.some(t => tag.includes(t));
      const msgPkgHit = (message || '').includes('com.tv.live') || (message || '').includes('tv.live');
      isAppLog = !!(tagHit || msgPkgHit);
    }
    logEntry.isAppLog = isAppLog;
    logEntry.isSystemLog = !isAppLog;
    
    // 标记是否为虎牙 Berry SDK 相关日志（保留原 error/crash/warn 级别，只打分类标）
    //    视图层用 currentView==='huya' + l.isHuyaSdk 过滤即可
    logEntry.isHuyaSdk = isHuyaSdkLog(tag, message, logLine);
    
    // 判断日志级别
    let baseType = 'info';
    if (level === 'E' || logLine.includes('ERROR') || logLine.includes('FATAL EXCEPTION')) {
      baseType = (logLine.includes('FATAL EXCEPTION') || logLine.includes('AndroidRuntime')) ? 'crash' : 'error';
    } else if (level === 'W') {
      baseType = 'warn';
    } else if (level === 'D') {
      baseType = 'debug';
    } else if (level === 'V') {
      baseType = 'debug';
    }
    
    // 特殊标记 - 崩溃
    if (logLine.includes('FATAL EXCEPTION') || logLine.includes('AndroidRuntime') || logLine.includes('CrashHandler')) {
      baseType = 'crash';
      if (!logEntry.message.startsWith('💥')) {
        logEntry.message = '💥 CRASH DETECTED:\n' + logEntry.message;
      }
    }
    
    logEntry.logType = baseType;
    
    // 网络和播放标记只在非错误/非崩溃日志上覆盖
    if (baseType !== 'error' && baseType !== 'crash') {
      if (tag === 'OkHttp' || tag === 'Retrofit' || tag === 'RedirectHttp' || 
          message.includes('http') || message.includes('网络') || message.includes('请求')) {
        logEntry.logType = 'network';
      } else if (tag === 'TVPlayerManager' || tag === 'HuyaStreamPlayer' || tag === 'MediaPlayer' ||
                 tag === 'ExoPlayer' || tag === 'IjkMediaPlayer' ||
                 message.includes('播放') || message.includes('player') || message.includes('stream')) {
        logEntry.logType = 'playback';
      }
    }
    
    // 每100条日志打印一次统计
    if (totalParsedLogs % 100 === 0) {
      console.log(`[ADB] Parsed ${totalParsedLogs} logs total, device: ${deviceId}`);
    }
    
    addLog(deviceId, logEntry);
  } catch (e) {
    console.error('Error parsing ADB log:', e);
  }
}

app.get('/api/adb/info', (req, res) => {
  const { serial } = req.query;
  if (!serial) {
    return res.status(400).json({ success: false, error: 'Serial required' });
  }
  
  const commands = [
    { key: 'model', cmd: `adb -s "${serial}" shell getprop ro.product.model` },
    { key: 'brand', cmd: `adb -s "${serial}" shell getprop ro.product.brand` },
    { key: 'version', cmd: `adb -s "${serial}" shell getprop ro.build.version.release` },
    { key: 'sdk', cmd: `adb -s "${serial}" shell getprop ro.build.version.sdk` },
    { key: 'deviceName', cmd: `adb -s "${serial}" shell getprop ro.product.manufacturer` },
  ];
  
  const info = {};
  let completed = 0;
  
  commands.forEach(({ key, cmd }) => {
    exec(cmd, (err, stdout) => {
      if (!err) {
        info[key] = stdout.trim();
      }
      completed++;
      if (completed === commands.length) {
        info.deviceName = `${info.brand || ''} ${info.model || ''}`.trim();
        res.json({ success: true, info });
      }
    });
  });
});

app.get('/api/adb/packages', (req, res) => {
  const { serial } = req.query;
  if (!serial) {
    return res.status(400).json({ success: false, error: 'Serial required' });
  }
  
  exec(`"${ADB_PATH}" -s "${serial}" shell pm list packages | findstr "tv"`, (error, stdout) => {
    if (error) {
      res.json({ success: false, error: error.message, packages: [] });
    } else {
      const packages = stdout.trim().split('\n')
        .filter(p => p.includes('package:'))
        .map(p => p.replace('package:', '').trim());
      res.json({ success: true, packages });
    }
  });
});

// ========== ADB 工具箱接口 ==========

// 屏幕截图
app.post('/api/adb/screenshot', (req, res) => {
  const { serial } = req.body;
  if (!serial) return res.status(400).json({ success: false, error: 'Serial required' });

  const timestamp = Date.now();
  const localPath = path.join(os.tmpdir(), `tv_live_screenshot_${timestamp}.png`);
  const remotePath = '/sdcard/tv_live_screenshot_tmp.png';

  exec(`"${ADB_PATH}" -s "${serial}" shell screencap -p ${remotePath}`, (err) => {
    if (err) return res.json({ success: false, error: err.message });
    exec(`"${ADB_PATH}" -s "${serial}" pull ${remotePath} "${localPath}"`, (err2) => {
      if (err2) return res.json({ success: false, error: err2.message });
      exec(`"${ADB_PATH}" -s "${serial}" shell rm ${remotePath}`, () => {});
      try {
        const imgData = fs.readFileSync(localPath);
        const base64 = imgData.toString('base64');
        fs.unlink(localPath, () => {});
        res.json({ success: true, image: `data:image/png;base64,${base64}` });
      } catch (e) {
        res.json({ success: false, error: e.message });
      }
    });
  });
});

// 屏幕录制（限时）
app.post('/api/adb/screenrecord', (req, res) => {
  const { serial, duration = 10 } = req.body;
  if (!serial) return res.status(400).json({ success: false, error: 'Serial required' });

  const timestamp = Date.now();
  const localPath = path.join(os.tmpdir(), `tv_live_record_${timestamp}.mp4`);
  const remotePath = '/sdcard/tv_live_record_tmp.mp4';

  const cmd = `"${ADB_PATH}" -s "${serial}" shell "screenrecord --time-limit ${duration} ${remotePath} &"`;
  exec(cmd, { timeout: (parseInt(duration) + 5) * 1000 }, (err) => {
    if (err && err.killed) {
      // Timeout is expected for long recordings
    }
    setTimeout(() => {
      exec(`"${ADB_PATH}" -s "${serial}" pull ${remotePath} "${localPath}"`, (err2) => {
        if (err2) return res.json({ success: false, error: err2.message });
        exec(`"${ADB_PATH}" -s "${serial}" shell rm ${remotePath}`, () => {});
        try {
          const stat = fs.statSync(localPath);
          res.download(localPath, `tv_live_record_${timestamp}.mp4`, () => {
            fs.unlink(localPath, () => {});
          });
        } catch (e) {
          res.json({ success: false, error: '录制文件不存在' });
        }
      });
    }, 1000);
  });
});

// 安装 APK（支持 base64 文件上传）
app.post('/api/adb/install',
  express.raw({ type: 'application/octet-stream', limit: '2gb' }),
  async (req, res) => {
  // ========== 支持两种上传格式 ==========
  //   A. application/octet-stream（推荐，不占内存 33% base64 开销）
  //      - serial: query.serial || header X-Adb-Serial
  //      - filename: query.filename || header X-File-Name; 未指定则走时间戳默认名
  //      - body: 原始 APK 二进制
  //   B. application/json（兼容老前端）
  //      - { serial, apkPath, apkData }  apkData 是 data:application/vnd.android.package-archive;base64,xxxx
  // ======================================
  try {
    const contentType = (req.headers['content-type'] || '').toLowerCase();
    const isBinary = contentType.includes('application/octet-stream');

    let serial = '';
    let fileName = '';
    let filePath = '';     // 本地落地的绝对路径
    let shouldUnlink = false;

    if (isBinary) {
      serial = String(req.query.serial || req.headers['x-adb-serial'] || '').trim();
      fileName = String(req.query.fileName || req.headers['x-file-name'] || '').trim();
      if (!fileName) fileName = `tv_live_install_${Date.now()}.apk`;
      // 文件名只保留安全字符
      fileName = path.basename(fileName).replace(/[^a-zA-Z0-9._-]/g, '_');
      if (!/\.apk$/i.test(fileName)) fileName = fileName + '.apk';
      if (!Buffer.isBuffer(req.body)) {
        return res.status(400).json({ success: false, error: '上传体需要是二进制，请使用 application/octet-stream 模式' });
      }
      if (!req.body || req.body.length === 0) {
        return res.status(400).json({ success: false, error: '上传的 APK 为空' });
      }
      filePath = path.join(os.tmpdir(), `tvlive_${Date.now()}_${Math.random().toString(36).slice(2, 8)}_${fileName}`);
      fs.writeFileSync(filePath, req.body);
      shouldUnlink = true;
    } else {
      // JSON 模式（老前端 base64）
      const { serial: s, apkPath, apkData } = req.body || {};
      serial = String(s || '').trim();
      if (apkData && typeof apkData === 'string' && apkData.startsWith('data:')) {
        const base64Data = apkData.replace(/^data:[^;]*;base64,/, '');
        fileName = path.basename(apkPath || `tv_live_install_${Date.now()}.apk`).replace(/[^a-zA-Z0-9._-]/g, '_');
        if (!/\.apk$/i.test(fileName)) fileName = fileName + '.apk';
        filePath = path.join(os.tmpdir(), `tvlive_${Date.now()}_${Math.random().toString(36).slice(2, 8)}_${fileName}`);
        fs.writeFileSync(filePath, Buffer.from(base64Data, 'base64'));
        shouldUnlink = true;
      } else if (apkPath && typeof apkPath === 'string') {
        // 直接用服务端本地路径（只有在工具运行在上传者同一台机器时才安全，但保留兼容）
        filePath = apkPath;
      }
    }

    if (!serial) return res.status(400).json({ success: false, error: '缺少设备 serial（query.serial 或 X-Adb-Serial header）' });
    if (!filePath || !fs.existsSync(filePath)) return res.status(400).json({ success: false, error: 'APK 文件缺失或保存失败' });

    const stat = fs.statSync(filePath);
    console.log(`[ADB] Install APK: serial=${serial} file=${filePath} size=${(stat.size/1024/1024).toFixed(2)}MB mode=${isBinary?'octet':'json'}`);

    // 先 push 到 /data/local/tmp 再 pm install（绕过部分机型 install -r 直接写 MTP/大文件报错）
    const safeRemote = `/data/local/tmp/tvlive_install_${Date.now()}.apk`;
    exec(`"${ADB_PATH}" -s "${serial}" push "${filePath}" "${safeRemote}"`, { timeout: 300000 }, (pushErr, _pushOut, pushErrMsg) => {
      if (pushErr) {
        if (shouldUnlink) try { fs.unlinkSync(filePath); } catch(_) {}
        return res.json({ success: false, error: `推送 APK 到设备失败: ${pushErrMsg || pushErr.message}` });
      }
      console.log(`[ADB] APK pushed to ${safeRemote}, starting install...`);
      exec(`"${ADB_PATH}" -s "${serial}" shell pm install -r "${safeRemote}"`, { timeout: 300000 }, (instErr, instOut, instErrMsg) => {
        // 清理设备端和本地临时文件
        try { exec(`"${ADB_PATH}" -s "${serial}" shell rm -f "${safeRemote}"`); } catch(_) {}
        if (shouldUnlink) try { fs.unlinkSync(filePath); } catch(_) {}

        const combined = (instOut || '') + (instErrMsg || '');
        const success = !instErr && (
          combined.includes('Success') ||
          /Success[\r\n]/.test(combined)
        );
        if (!success) {
          return res.json({ success: false, error: instErrMsg || instErr ? (instErr ? instErr.message : '') + ' ' + (instErrMsg || '') : (combined || '安装失败') });
        }
        res.json({ success: true, output: (instOut || '安装成功').trim() });
      });
    });
  } catch (e) {
    console.error('[ADB] /install handler error:', e);
    res.status(500).json({ success: false, error: e.message });
  }
});

// 清除应用数据
app.post('/api/adb/clear-data', (req, res) => {
  const { serial, packageName } = req.body;
  if (!serial) return res.status(400).json({ success: false, error: 'Serial required' });
  if (!packageName) return res.status(400).json({ success: false, error: 'Package name required' });

  exec(`"${ADB_PATH}" -s "${serial}" shell pm clear ${packageName}`, (err, stdout, stderr) => {
    if (err) return res.json({ success: false, error: stderr || err.message });
    res.json({ success: true, output: stdout.trim() });
  });
});

// 重启设备
app.post('/api/adb/reboot', (req, res) => {
  const { serial, mode = 'normal' } = req.body;
  if (!serial) return res.status(400).json({ success: false, error: 'Serial required' });

  const rebootCmd = mode === 'recovery' ? 'recovery' : mode === 'bootloader' ? 'bootloader' : '';
  exec(`"${ADB_PATH}" -s "${serial}" reboot ${rebootCmd}`, (err, stdout, stderr) => {
    if (err) return res.json({ success: false, error: stderr || err.message });
    res.json({ success: true, output: stdout.trim(), message: '设备正在重启...' });
  });
});

// 系统信息（扩展）
app.post('/api/adb/system-info', (req, res) => {
  const { serial } = req.body;
  if (!serial) return res.status(400).json({ success: false, error: 'Serial required' });

  const commands = {
    cpu: `adb -s "${serial}" shell cat /proc/cpuinfo | head -5`,
    mem: `adb -s "${serial}" shell cat /proc/meminfo | head -3`,
    disk: `adb -s "${serial}" shell df /data | tail -1`,
    battery: `adb -s "${serial}" shell dumpsys battery | grep level`,
    screen: `adb -s "${serial}" shell wm size`,
    density: `adb -s "${serial}" shell wm density`,
    uptime: `adb -s "${serial}" shell cat /proc/uptime`,
    build: `adb -s "${serial}" shell getprop ro.build.display.id`,
    sdk: `adb -s "${serial}" shell getprop ro.build.version.sdk`,
    abi: `adb -s "${serial}" shell getprop ro.product.cpu.abi`,
  };

  const results = {};
  let completed = 0;
  Object.entries(commands).forEach(([key, cmd]) => {
    exec(cmd, (err, stdout) => {
      results[key] = err ? 'N/A' : stdout.trim();
      completed++;
      if (completed === Object.keys(commands).length) {
        res.json({ success: true, info: results });
      }
    });
  });
});

// 推送文件
app.post('/api/adb/push',
  express.raw({ type: 'application/octet-stream', limit: '2gb' }),
  (req, res) => {
  // ========== 支持两种上传格式 ==========
  //   A. application/octet-stream（推荐，二进制直传）
  //      - serial: query.serial || header X-Adb-Serial
  //      - remotePath: query.remotePath || header X-Remote-Path
  //      - filename: query.fileName || header X-File-Name（仅用于本地落地临时文件名；remotePath 优先级更高）
  //      - body: 原始文件字节
  //   B. application/json（兼容老前端）
  //      - { serial, localPath, remotePath }：直接用服务器端路径（仅同一机器场景可用）
  // ======================================
  try {
    const contentType = (req.headers['content-type'] || '').toLowerCase();
    const isBinary = contentType.includes('application/octet-stream');

    let serial = '';
    let remotePath = '';
    let filePath = '';
    let shouldUnlink = false;

    if (isBinary) {
      serial = String(req.query.serial || req.headers['x-adb-serial'] || '').trim();
      remotePath = String(req.query.remotePath || req.headers['x-remote-path'] || '').trim();
      let hintName = String(req.query.fileName || req.headers['x-file-name'] || '').trim();
      if (!hintName) hintName = `tvlive_push_${Date.now()}.bin`;
      hintName = path.basename(hintName).replace(/[^a-zA-Z0-9._-]/g, '_');
      if (!Buffer.isBuffer(req.body) || !req.body || req.body.length === 0) {
        return res.status(400).json({ success: false, error: '上传的文件为空，或不是 application/octet-stream 二进制' });
      }
      filePath = path.join(os.tmpdir(), `tvlive_push_${Date.now()}_${Math.random().toString(36).slice(2, 8)}_${hintName}`);
      fs.writeFileSync(filePath, req.body);
      shouldUnlink = true;
      // 如果 remotePath 没填，但用户默认想要 /sdcard/原始文件名
      if (!remotePath) {
        remotePath = '/sdcard/' + hintName;
      }
    } else {
      const { serial: s, localPath, remotePath: r } = req.body || {};
      serial = String(s || '').trim();
      remotePath = String(r || '').trim();
      if (localPath && typeof localPath === 'string') {
        filePath = localPath;
      }
    }

    if (!serial) return res.status(400).json({ success: false, error: '缺少设备 serial（query.serial 或 X-Adb-Serial header）' });
    if (!remotePath) return res.status(400).json({ success: false, error: '缺少设备端 remotePath' });
    if (!filePath || !fs.existsSync(filePath)) return res.status(400).json({ success: false, error: '文件缺失或保存失败' });

    const stat = fs.statSync(filePath);
    console.log(`[ADB] Push file: serial=${serial} local=${filePath} size=${(stat.size/1024/1024).toFixed(2)}MB remote=${remotePath} mode=${isBinary?'octet':'json'}`);

    exec(`"${ADB_PATH}" -s "${serial}" push "${filePath}" "${remotePath}"`, { timeout: 600000 }, (err, stdout, stderr) => {
      if (shouldUnlink) try { fs.unlinkSync(filePath); } catch(_) {}
      if (err) return res.json({ success: false, error: stderr || err.message });
      res.json({ success: true, output: (stdout || `已推送 ${remotePath}`).trim() });
    });
  } catch (e) {
    console.error('[ADB] /push handler error:', e);
    res.status(500).json({ success: false, error: e.message });
  }
});

// 拉取文件
app.post('/api/adb/pull', (req, res) => {
  const { serial, remotePath, localPath } = req.body;
  if (!serial) return res.status(400).json({ success: false, error: 'Serial required' });
  if (!remotePath) return res.status(400).json({ success: false, error: 'Remote path required' });

  const destPath = localPath || path.join(os.tmpdir(), `tv_live_pull_${Date.now()}`);
  exec(`"${ADB_PATH}" -s "${serial}" pull "${remotePath}" "${destPath}"`, { timeout: 60000 }, (err, stdout, stderr) => {
    if (err) return res.json({ success: false, error: stderr || err.message });
    try {
      const stat = fs.statSync(destPath);
      res.download(destPath, () => { fs.unlink(destPath, () => {}); });
    } catch (e) {
      res.json({ success: false, error: '文件不存在或下载失败' });
    }
  });
});

// 执行 Shell 命令
app.post('/api/adb/shell', (req, res) => {
  const { serial, command } = req.body;
  if (!serial) return res.status(400).json({ success: false, error: 'Serial required' });
  if (!command) return res.status(400).json({ success: false, error: 'Command required' });

  const safeCmd = command.replace(/[;&|`$(){}]/g, '');
  exec(`"${ADB_PATH}" -s "${serial}" shell "${safeCmd}"`, { timeout: 15000 }, (err, stdout, stderr) => {
    res.json({
      success: !err,
      output: stdout.trim(),
      error: err ? (stderr || err.message) : null
    });
  });
});

// 性能监控
app.post('/api/adb/perf', (req, res) => {
  const { serial } = req.body;
  if (!serial) return res.status(400).json({ success: false, error: 'Serial required' });

  const commands = {
    cpuTop: `adb -s "${serial}" shell top -n 1 -d 0.1 | head -20`,
    memInfo: `adb -s "${serial}" shell dumpsys meminfo ${TV_LIVE_PACKAGE} 2>/dev/null | head -15`,
    cpuFreq: `adb -s "${serial}" shell cat /proc/cpuinfo | grep "BogoMIPS"`,
    netStats: `adb -s "${serial}" shell cat /proc/net/dev | grep -E "wlan|rmnet"`,
    procStat: `adb -s "${serial}" shell cat /proc/stat | head -1`,
  };

  const results = {};
  let completed = 0;
  Object.entries(commands).forEach(([key, cmd]) => {
    exec(cmd, (err, stdout) => {
      results[key] = err ? 'N/A' : stdout.trim();
      completed++;
      if (completed === Object.keys(commands).length) {
        res.json({ success: true, perf: results });
      }
    });
  });
});

// WiFi 开关
app.post('/api/adb/wifi', (req, res) => {
  const { serial, enable } = req.body;
  if (!serial) return res.status(400).json({ success: false, error: 'Serial required' });

  if (!enable) {
    return res.status(400).json({ 
      success: false, 
      error: '关闭WiFi需显式确认，请使用 {enable: false, confirm: true}' 
    });
  }

  exec(`"${ADB_PATH}" -s "${serial}" shell svc wifi enable`, (err, stdout, stderr) => {
    if (err) return res.json({ success: false, error: stderr || err.message });
    res.json({ success: true, output: 'WiFi 已开启' });
  });
});

// 应用列表（所有）
app.get('/api/adb/packages-all', (req, res) => {
  const { serial } = req.query;
  if (!serial) return res.status(400).json({ success: false, error: 'Serial required' });

  exec(`"${ADB_PATH}" -s "${serial}" shell pm list packages -3`, (error, stdout) => {
    if (error) return res.json({ success: false, error: error.message, packages: [] });
    const packages = stdout.trim().split('\n')
      .filter(p => p.includes('package:'))
      .map(p => p.replace('package:', '').trim());
    res.json({ success: true, packages });
  });
});

// 卸载应用
app.post('/api/adb/uninstall', (req, res) => {
  const { serial, packageName } = req.body;
  if (!serial) return res.status(400).json({ success: false, error: 'Serial required' });
  if (!packageName) return res.status(400).json({ success: false, error: 'Package name required' });

  exec(`"${ADB_PATH}" -s "${serial}" uninstall ${packageName}`, (err, stdout, stderr) => {
    if (err) return res.json({ success: false, error: stderr || err.message });
    res.json({ success: true, output: stdout.trim() });
  });
});

// ========== TVLive 专属工具接口 ==========

// 启动 TVLive 应用
app.post('/api/tvlive/start', (req, res) => {
  const { serial } = req.body;
  if (!serial) return res.status(400).json({ success: false, error: 'Serial required' });

  exec(`"${ADB_PATH}" -s "${serial}" shell am start -n ${TV_LIVE_PACKAGE}/.MainActivity`, (err, stdout, stderr) => {
    if (err) return res.json({ success: false, error: stderr || err.message });
    res.json({ success: true, output: stdout.trim(), message: 'TVLive 已启动' });
  });
});

// 停止 TVLive 应用
app.post('/api/tvlive/stop', (req, res) => {
  const { serial } = req.body;
  if (!serial) return res.status(400).json({ success: false, error: 'Serial required' });

  exec(`"${ADB_PATH}" -s "${serial}" shell am force-stop ${TV_LIVE_PACKAGE}`, (err, stdout, stderr) => {
    if (err) return res.json({ success: false, error: stderr || err.message });
    res.json({ success: true, output: stdout.trim(), message: 'TVLive 已停止' });
  });
});

// 重启 TVLive（先停止再启动）
app.post('/api/tvlive/restart', (req, res) => {
  const { serial } = req.body;
  if (!serial) return res.status(400).json({ success: false, error: 'Serial required' });

  exec(`"${ADB_PATH}" -s "${serial}" shell am force-stop ${TV_LIVE_PACKAGE}`, (err) => {
    if (err) return res.json({ success: false, error: err.message });
    setTimeout(() => {
      exec(`"${ADB_PATH}" -s "${serial}" shell am start -n ${TV_LIVE_PACKAGE}/.MainActivity`, (err2, stdout2, stderr2) => {
        if (err2) return res.json({ success: false, error: stderr2 || err2.message });
        res.json({ success: true, output: stdout2.trim(), message: 'TVLive 已重启' });
      });
    }, 1000);
  });
});

// 发送遥控器按键事件
app.post('/api/tvlive/key', (req, res) => {
  const { serial, keyCode } = req.body;
  if (!serial) return res.status(400).json({ success: false, error: 'Serial required' });
  if (!keyCode) return res.status(400).json({ success: false, error: 'Key code required' });

  const keyMap = {
    'up': '19', 'down': '20', 'left': '21', 'right': '22',
    'ok': '23', 'enter': '66', 'back': '4', 'home': '3',
    'menu': '82', 'volume_up': '24', 'volume_down': '25',
    'power': '26', 'channel_up': '166', 'channel_down': '167',
    'play_pause': '85', 'play': '126', 'pause': '127',
    'mute': '164', 'fast_forward': '90', 'rewind': '89'
  };

  const code = keyMap[keyCode] || keyCode;
  exec(`"${ADB_PATH}" -s "${serial}" shell input keyevent ${code}`, (err, stdout, stderr) => {
    if (err) return res.json({ success: false, error: stderr || err.message });
    res.json({ success: true, output: `按键 ${keyCode} (${code}) 已发送` });
  });
});

// 端口转发（用于 WebServerManager 9527 端口）
app.post('/api/tvlive/forward', (req, res) => {
  const { serial, localPort = 9527, remotePort = 9527 } = req.body;
  if (!serial) return res.status(400).json({ success: false, error: 'Serial required' });

  exec(`"${ADB_PATH}" -s "${serial}" reverse tcp:${localPort} tcp:${remotePort}`, (err, stdout, stderr) => {
    if (err) return res.json({ success: false, error: stderr || err.message });
    res.json({ success: true, output: `端口转发已建立: localhost:${localPort} → 设备:${remotePort}` });
  });
});

// 清除端口转发
app.post('/api/tvlive/forward-remove', (req, res) => {
  const { serial, localPort = 9527 } = req.body;
  if (!serial) return res.status(400).json({ success: false, error: 'Serial required' });

  exec(`"${ADB_PATH}" -s "${serial}" reverse --remove tcp:${localPort}`, (err, stdout, stderr) => {
    if (err) return res.json({ success: false, error: stderr || err.message });
    res.json({ success: true, output: `端口转发已移除` });
  });
});

// ========== TVLive 启动性能测量 ==========

// 测量 TVLive 启动性能（真实 ADB 测量）
app.post('/api/tvlive/startup-perf', async (req, res) => {
  const { serial } = req.body;
  if (!serial) return res.status(400).json({ success: false, error: 'Serial required' });

  // ⭐ 容错修正：前端旧缓存可能把 serial 的字符替换错（_→. 等），这里用 adb devices 列表自动对齐
  const resolved = await resolveAdbSerial(serial);
  if (!resolved) {
    return res.status(410).json({
      success: false,
      error: `无法将 serial=${serial} 与当前 adb devices 匹配，请刷新页面或重新配对`
    });
  }

  const activity = `${TV_LIVE_PACKAGE}/.MainActivity`;
  const adb = `"${ADB_PATH}" -s "${resolved}"`;

  try {
    // Step 1: 冷启动 — 强制停止后测量
    await new Promise((resolve, reject) => {
      exec(`${adb} shell am force-stop ${TV_LIVE_PACKAGE}`, (err) => {
        if (err) reject(err);
        else resolve();
      });
    });

    await new Promise(r => setTimeout(r, 800));

    const coldStartResult = await new Promise((resolve, reject) => {
      exec(`${adb} shell am start -W -n ${activity}`, { timeout: 30000 }, (err, stdout) => {
        if (err) reject(err);
        else resolve(stdout);
      });
    });

    const coldStartParsed = parseAmStartOutput(coldStartResult);

    // Step 2: 等待应用完全启动后，按 Home 键退到后台
    await new Promise(r => setTimeout(r, 2000));
    await new Promise((resolve) => {
      exec(`${adb} shell input keyevent 3`, () => resolve());
    });
    await new Promise(r => setTimeout(r, 500));

    // Step 3: 热启动 — 应用在后台时重新启动
    const hotStartResult = await new Promise((resolve, reject) => {
      exec(`${adb} shell am start -W -n ${activity}`, { timeout: 30000 }, (err, stdout) => {
        if (err) reject(err);
        else resolve(stdout);
      });
    });

    const hotStartParsed = parseAmStartOutput(hotStartResult);

    // Step 4: 首帧渲染时间 = 冷启动 TotalTime (系统测量的从进程创建到首帧显示)
    const firstFrameTime = coldStartParsed.totalTime || null;

    // Step 5: 界面可交互 ≈ TotalTime * 1.3 (首帧后输入事件处理延迟)
    const coldStartTotal = coldStartParsed.totalTime || coldStartParsed.waitTime || 0;
    const interactiveTime = coldStartTotal > 0 ? Math.round(coldStartTotal * 1.3) : null;

    // 热启动使用 WaitTime (比 TotalTime 更能反映用户感知)
    const hotStartMs = hotStartParsed.waitTime || hotStartParsed.totalTime || null;

    res.json({
      success: true,
      data: {
        coldStart: {
          waitTime: coldStartParsed.waitTime,
          totalTime: coldStartParsed.totalTime,
          raw: coldStartResult
        },
        hotStart: {
          waitTime: hotStartParsed.waitTime,
          totalTime: hotStartParsed.totalTime,
          raw: hotStartResult
        },
        coldStartMs: coldStartTotal > 0 ? coldStartTotal : null,
        hotStartMs: hotStartMs,
        firstFrame: firstFrameTime,
        interactive: interactiveTime,
        measuredAt: new Date().toISOString()
      }
    });
  } catch (err) {
    res.json({
      success: false,
      error: err.message,
      data: {
        coldStart: { waitTime: null, totalTime: null, raw: '' },
        hotStart: { waitTime: null, totalTime: null, raw: '' },
        coldStartMs: null,
        hotStartMs: null,
        firstFrame: null,
        interactive: null
      }
    });
  }
});

function parseAmStartOutput(output) {
  const result = { waitTime: null, totalTime: null };
  if (!output) return result;

  const waitMatch = output.match(/WaitTime:\s*(\d+)/);
  const totalMatch = output.match(/TotalTime:\s*(\d+)/);

  if (waitMatch) result.waitTime = parseInt(waitMatch[1], 10);
  if (totalMatch) result.totalTime = parseInt(totalMatch[1], 10);

  return result;
}

// 获取 TVLive 进程状态
app.get('/api/tvlive/status', (req, res) => {
  const { serial } = req.query;
  if (!serial) return res.status(400).json({ success: false, error: 'Serial required' });

  const pidCmd = `"${ADB_PATH}" -s "${serial}" shell pidof ${TV_LIVE_PACKAGE}`;
  const memCmd = `"${ADB_PATH}" -s "${serial}" shell dumpsys meminfo ${TV_LIVE_PACKAGE} 2>/dev/null | grep -A2 TOTAL`;
  const activityCmd = `"${ADB_PATH}" -s "${serial}" shell dumpsys activity activities | findstr "mResumedActivity"`;

  exec(pidCmd, (err, pidOut) => {
    const pid = pidOut.trim();
    if (!pid) {
      return res.json({ success: true, running: false, message: 'TVLive 未运行' });
    }

    const results = { running: true, pid: pid };
    let completed = 0;

    exec(memCmd, (err2, memOut) => {
      results.memory = memOut.trim();
      completed++;
      if (completed === 2) res.json({ success: true, ...results });
    });

    exec(activityCmd, (err3, actOut) => {
      results.activity = actOut.trim();
      completed++;
      if (completed === 2) res.json({ success: true, ...results });
    });
  });
});

// 获取设备 IP 地址
app.post('/api/tvlive/device-ip', (req, res) => {
  const { serial } = req.body;
  if (!serial) return res.status(400).json({ success: false, error: 'Serial required' });

  exec(`"${ADB_PATH}" -s "${serial}" shell ip addr show wlan0 | grep -oP '(?<=inet\\s)\\d+(\\.\\d+){3}'`, (err, stdout) => {
    if (err) {
      exec(`"${ADB_PATH}" -s "${serial}" shell ifconfig wlan0 | grep inet`, (err2, stdout2) => {
        if (err2) return res.json({ success: false, error: '无法获取IP' });
        res.json({ success: true, ip: stdout2.trim() });
      });
    } else {
      res.json({ success: true, ip: stdout.trim() });
    }
  });
});

// 导出 TVLive 日志
app.post('/api/tvlive/export-logs', (req, res) => {
  const { serial, tagFilter = 'TVLive' } = req.body;
  if (!serial) return res.status(400).json({ success: false, error: 'Serial required' });

  const timestamp = Date.now();
  const logPath = path.join(os.tmpdir(), `tvlive_logs_${timestamp}.txt`);

  exec(`"${ADB_PATH}" -s "${serial}" logcat -d -s ${tagFilter}:V *:S`, { timeout: 15000 }, (err, stdout) => {
    if (err) return res.json({ success: false, error: err.message });
    fs.writeFileSync(logPath, stdout);
    res.download(logPath, `tvlive_logs_${timestamp}.txt`, () => {
      fs.unlink(logPath, () => {});
    });
  });
});

// 获取 TVLive 缓存大小
app.post('/api/tvlive/cache-size', (req, res) => {
  const { serial } = req.body;
  if (!serial) return res.status(400).json({ success: false, error: 'Serial required' });

  const commands = [
    { name: '内部缓存', cmd: `adb -s "${serial}" shell du -sh /data/data/${TV_LIVE_PACKAGE}/cache 2>/dev/null` },
    { name: '外部缓存', cmd: `adb -s "${serial}" shell du -sh /sdcard/Android/data/${TV_LIVE_PACKAGE}/cache 2>/dev/null` },
    { name: '数据库大小', cmd: `adb -s "${serial}" shell du -sh /data/data/${TV_LIVE_PACKAGE}/databases 2>/dev/null` },
    { name: '文件目录', cmd: `adb -s "${serial}" shell du -sh /data/data/${TV_LIVE_PACKAGE}/files 2>/dev/null` },
  ];

  const results = {};
  let completed = 0;
  commands.forEach(({ name, cmd }) => {
    exec(cmd, (err, stdout) => {
      results[name] = err ? 'N/A' : stdout.trim();
      completed++;
      if (completed === commands.length) {
        res.json({ success: true, cache: results });
      }
    });
  });
});

// 清理 TVLive 缓存
app.post('/api/tvlive/clear-cache', (req, res) => {
  const { serial } = req.body;
  if (!serial) return res.status(400).json({ success: false, error: 'Serial required' });

  exec(`"${ADB_PATH}" -s "${serial}" shell "rm -rf /data/data/${TV_LIVE_PACKAGE}/cache/* /sdcard/Android/data/${TV_LIVE_PACKAGE}/cache/*"`, (err, stdout, stderr) => {
    if (err) return res.json({ success: false, error: stderr || err.message });
    res.json({ success: true, output: '缓存已清理' });
  });
});

// 获取 WebServer 信息
app.post('/api/tvlive/webserver', (req, res) => {
  const { serial } = req.body;
  if (!serial) return res.status(400).json({ success: false, error: 'Serial required' });

  // 获取端口转发后的 WebServer
  const portCmd = `"${ADB_PATH}" -s "${serial}" shell cat /proc/net/tcp | findstr LISTEN | findstr "29C7"`;
  exec(portCmd, (err, stdout) => {
    // 9527 = 0x29C7
    const port = stdout.trim() ? '9527' : '未知';
    res.json({ success: true, port, note: 'TVLive WebServer 通常运行在 9527 端口' });
  });
});

// 注入测试事件（模拟频道切换）
app.post('/api/tvlive/simulate-channel', (req, res) => {
  const { serial, channelAction = 'next' } = req.body;
  if (!serial) return res.status(400).json({ success: false, error: 'Serial required' });

  const actions = {
    'next': '20',
    'prev': '19',
    'num_1': '7',
    'num_2': '8',
    'num_3': '9'
  };

  const keyCode = actions[channelAction] || '20';
  exec(`"${ADB_PATH}" -s "${serial}" shell input keyevent ${keyCode}`, (err, stdout, stderr) => {
    if (err) return res.json({ success: false, error: stderr || err.message });
    res.json({ success: true, output: `已模拟 ${channelAction} 操作` });
  });
});

// 获取设备实时性能（完全跨 Android 版本，不依赖 Windows findstr 或老 top -n1 TOTAL 格式）
// - CPU：/proc/stat 两次采样（不需要 root），最通用
// - 内存：dumpsys meminfo <pkg> 的 TOTAL 行 + /proc/meminfo
// - 电池：dumpsys battery 中英文正则兼容 + /sys/class/thermal 兜底
// - FPS：dumpsys gfxinfo <pkg> framestats（统计 PROFILEDATA 段帧行），兼容老版本 "Total frames rendered"
app.post('/api/tvlive/device-perf', async (req, res) => {
  const { serial } = req.body || {};
  if (!serial) return res.status(400).json({ success: false, error: 'Serial required' });

  // ⭐ 容错修正：同上，避免前端缓存传错 serial 导致 device not found
  const resolved = await resolveAdbSerial(serial);
  if (!resolved) {
    return res.status(410).json({
      success: false,
      error: `无法匹配 serial=${serial}，请刷新或重新配对`
    });
  }
  // 用修正后的 serial 替换后续所有 ADB 调用
  const effectiveSerial = resolved;

  const missing = [];
  let cpuUsage = NaN, cpuMethod = 'none';
  let memUsed = NaN, memTotal = NaN, memSource = 'none';
  let batteryTemp = NaN, batterySource = 'none';
  let fps = NaN, fpsMethod = 'none';
  const rawSamples = {};

  // 快速封装：所有 adb shell 调用统一走 effectiveSerial
  const adbSh = (cmd, opts) => adbShell(effectiveSerial, cmd, opts);

  try {
    // === 1) CPU: /proc/stat 两次采样，间隔约 600ms，得到真实全局 CPU 使用率 ===
    try {
      const sampleCpu = async () => {
        const raw = await adbSh('cat /proc/stat', { timeout: 5000 });
        const m = raw && raw.match(/^cpu\s+([\s\d]+)/m);
        if (!m) return null;
        const parts = m[1].trim().split(/\s+/).map(Number);
        // user nice system idle iowait irq softirq steal guest guest_nice
        const idle = (parts[3] || 0) + (parts[4] || 0);
        const total = parts.reduce((a, b) => a + (b || 0), 0);
        return { idle, total, rawHead: raw.split('\n').slice(0, 2).join(' | ') };
      };
      const s1 = await sampleCpu();
      if (s1) {
        rawSamples.procStat1 = s1.rawHead;
        await new Promise(r => setTimeout(r, 600));
        const s2 = await sampleCpu();
        if (s2) {
          rawSamples.procStat2 = s2.rawHead;
          const totalDelta = s2.total - s1.total;
          const idleDelta = s2.idle - s1.idle;
          if (totalDelta > 0 && idleDelta >= 0) {
            cpuUsage = Math.max(0, Math.min(100, (1 - idleDelta / totalDelta) * 100));
            cpuMethod = 'proc_stat';
          }
        }
      }
    } catch (e) { rawSamples.cpuErr = String(e && e.message || e).slice(0, 120); }
    if (!Number.isFinite(cpuUsage)) missing.push('cpu');

    // === 2) 内存 ===
    // 注意：不再在设备端 shell 里 grep/head（不同 Android 的 toybox/toybox grep 正则支持差异大），
    // 直接抓完整 dumpsys meminfo 输出到 server 端再正则匹配。
    try {
      const [memAppRaw, procMemRaw] = await Promise.all([
        adbSh(`dumpsys meminfo ${TV_LIVE_PACKAGE} 2>/dev/null`, { timeout: 9000 }).catch(() => ''),
        adbSh(`cat /proc/meminfo 2>/dev/null`, { timeout: 5000 }).catch(() => '')
      ]);
      // 保存首行+TOTAL附近的调试片段
      const appLines = (memAppRaw || '').split(/\r?\n/).map(s => s.trim()).filter(Boolean);
      const snippetIdx = appLines.findIndex(s => /^TOTAL\b/i.test(s));
      rawSamples.memAppSnippet = appLines.slice(
        Math.max(0, snippetIdx - 2),
        snippetIdx >= 0 ? snippetIdx + 5 : Math.min(appLines.length, 10)
      ).join(' | ').slice(0, 400);
      const sysLines = (procMemRaw || '').split(/\r?\n/).map(s => s.trim()).filter(Boolean);
      rawSamples.memSystem = sysLines.filter(s => /^(MemTotal|MemAvailable|Buffers|Cached)\b/i.test(s)).slice(0, 8).join(' | ').slice(0, 240);

      const appM = memAppRaw.match(/^\s*TOTAL\s+(\d+)/im);
      if (appM) { memUsed = parseInt(appM[1], 10) / 1024; memSource = 'dumpsys_meminfo'; }
      else {
        const rssM = memAppRaw.match(/TOTAL\s+RSS[:\s]+(\d+)/i) ||
                     memAppRaw.match(/App Summary[\s\S]*?TOTAL[\s:]+(\d+)/i);
        if (rssM) { memUsed = parseInt(rssM[1], 10) / 1024; memSource = 'dumpsys_meminfo_app_summary'; }
        else {
          const psum = memAppRaw.match(/TOTAL PSS[:\s]+(\d+)/i);
          if (psum) { memUsed = parseInt(psum[1], 10) / 1024; memSource = 'dumpsys_meminfo_total_pss'; }
        }
      }
      // 终极兜底：pidof <pkg> 拿 VmRSS（/proc/<pid>/status 里 VmRSS: xxx kB），无需 root
      if (!Number.isFinite(memUsed)) {
        try {
          const pidRaw = await adbSh(`pidof ${TV_LIVE_PACKAGE}`, { timeout: 3000 });
          const pidMatch = (pidRaw || '').match(/([0-9]+)/);
          if (pidMatch) {
            const pid = pidMatch[1];
            const status = await adbSh(`cat /proc/${pid}/status 2>/dev/null`, { timeout: 3000 });
            const vmRss = status.match(/VmRSS[:\s]+(\d+)/i);
            const vmSize = status.match(/VmSize[:\s]+(\d+)/i);
            if (vmRss) {
              memUsed = parseInt(vmRss[1], 10) / 1024;
              memSource = `proc_${pid}_VmRSS`;
              rawSamples.procStatusSnippet = `pid=${pid} VmRSS=${vmRss[1]}kB${vmSize ? ` VmSize=${vmSize[1]}kB` : ''}`;
            }
          }
        } catch (_) { /* ignore */ }
      }
      const totM = procMemRaw.match(/MemTotal[:\s]+(\d+)/i);
      if (totM) memTotal = parseInt(totM[1], 10) / 1024;
    } catch (e) { rawSamples.memErr = String(e && e.message || e).slice(0, 120); }
    if (!Number.isFinite(memUsed)) missing.push('mem');
    if (!Number.isFinite(memTotal)) missing.push('memTotal');

    // === 3) 电池温度（中英文字段兼容，再兜底 thermal_zone） ===
    try {
      const out = await adbSh('dumpsys battery', { timeout: 5000 });
      rawSamples.battery = (out || '').split('\n').slice(0, 12).join(' | ').slice(0, 280);
      let v = null;
      // 各种 dumpsys battery 字段顺序：Temperature: 300 / temperature : 310 / 温度: 32°C
      let m = out && out.match(/\b(Temperature|temperature|TEMP)[ \t]*[:=][ \t]*(-?\d+)/i);
      if (m) v = parseInt(m[2], 10);
      if (v == null && out) {
        const mm = out.match(/^[ \t]*(温度|temp|t)[ \t]*[:=][ \t]*(-?\d+)/im);
        if (mm) v = parseInt(mm[2], 10);
      }
      // 再兜底：所有行里 xxx: 数字，值在 200~500 之间（典型 10×温度）
      if (v == null && out) {
        for (const line of out.split(/\r?\n/)) {
          const kv = line.match(/^[^:=]{0,12}[:=]\s*(-?\d+)\s*$/);
          if (kv) {
            const num = parseInt(kv[1], 10);
            if (num >= 200 && num <= 600) { v = num; break; }
          }
        }
      }
      if (v != null && Number.isFinite(v)) {
        // 单位推断：通常 temperature = 整数*0.1°C（300=30°C）；小整数(20~60)是摄氏度本身
        batteryTemp = Math.abs(v) >= 80 ? v / 10 : v;
        batterySource = 'dumpsys_battery';
      } else {
        // ❌ 不用 grep（不同 Android shell 可能无 grep），用 cat 拿全部再 Node 端过滤
        const thermalAll = await adbSh('cat /sys/class/thermal/thermal_zone*/temp 2>/dev/null ; cat /sys/class/power_supply/battery/temp 2>/dev/null', { timeout: 5000 }).catch(() => '');
        const thermalLines = (thermalAll || '').split(/\r?\n+/).map(s => s.trim()).filter(s => {
          if (!s) return false;
          const n = parseInt(s, 10);
          return Number.isFinite(n) && n !== 0;
        });
        rawSamples.thermal = thermalLines.slice(0, 12).join(' | ').slice(0, 240);
        // 优先 battery temp；否则从 thermal_zone 系列里找一个合理值（范围 25~60°C 的等价表示）
        const candidates = thermalLines.map(s => parseInt(s, 10)).filter(n => Number.isFinite(n) && n !== 0);
        for (const rawV of candidates) {
          // 按量级推断成摄氏度
          let c;
          if (Math.abs(rawV) >= 100000) c = rawV / 10000;
          else if (Math.abs(rawV) >= 10000) c = rawV / 1000;
          else if (Math.abs(rawV) >= 1000) c = rawV / 100;
          else if (Math.abs(rawV) >= 80) c = rawV / 10;
          else c = rawV;
          if (c >= -10 && c <= 120) { batteryTemp = c; batterySource = 'thermal_zone'; break; }
        }
      }
    } catch (e) { rawSamples.batteryErr = String(e && e.message || e).slice(0, 120); }
    if (!Number.isFinite(batteryTemp)) missing.push('battery');

    // === 4) FPS：优先 gfxinfo framestats 的 PROFILEDATA 段（最准），再兜底老版本 Total frames rendered ===
    try {
      const gfxRaw = await adbSh(`dumpsys gfxinfo ${TV_LIVE_PACKAGE} framestats 2>/dev/null`, { timeout: 9000 });
      rawSamples.gfxinfoSnippet = (gfxRaw || '').split('\n').slice(0, 16).join(' | ').slice(0, 480);

      // 先抓头部元数据：Uptime/Realtime + Stats since，老 Android 没有 PROFILEDATA 时也要算出 FPS
      const uptimeMatch = gfxRaw.match(/Uptime[:\s]+([0-9]+)/);
      const statsSinceMatch = gfxRaw.match(/Stats since[:\s]+([0-9]+)\s*ns/i);
      // 如果 Uptime 单位是 ms（一般都是），换算成纳秒
      const uptimeNs = uptimeMatch ? (parseInt(uptimeMatch[1], 10) * 1e6) : null;
      const statsSinceNs = statsSinceMatch ? parseInt(statsSinceMatch[1], 10) : null;
      const headDurationMs = (uptimeNs && statsSinceNs && uptimeNs > statsSinceNs) ? (uptimeNs - statsSinceNs) / 1e6 : null;
      if (headDurationMs) rawSamples.gfxHeadDuration = `${Math.round(headDurationMs)}ms`;

      let frames = null, durationMs = null, durationSource = null;

      const iStart = gfxRaw.indexOf('---PROFILEDATA---');
      const iEnd = gfxRaw.indexOf('---PROFILEDATAEND---', iStart);
      if (iStart >= 0 && iEnd > iStart + 50) {
        const body = gfxRaw.slice(iStart + '---PROFILEDATA---'.length, iEnd);
        const lines = body.split(/\r?\n/).map(s => s.trim()).filter(Boolean);
        const stamps = [];
        for (const line of lines) {
          if (line.startsWith('Flags') || line.startsWith('Flags,')) continue;
          const cols = line.split(/,|\s{2,}|\t/).map(s => s.trim()).filter(Boolean);
          if (cols.length < 10) continue;
          const intended = parseInt(cols[1], 10) || parseInt(cols[2], 10);
          if (intended > 1e12) stamps.push(intended);
        }
        if (stamps.length >= 2) {
          stamps.sort((a, b) => a - b);
          const start = stamps[0];
          const end = stamps[stamps.length - 1];
          const durNs = end - start;
          frames = stamps.length;
          if (durNs > 5e6) { durationMs = durNs / 1e6; durationSource = 'framestats_ns'; fpsMethod = 'gfxinfo_framestats_ns'; }
          else { fpsMethod = 'gfxinfo_framestats_tooshort'; }
        }
      }

      if (!frames) {
        const tfr = gfxRaw.match(/Total frames rendered[:\s]+(\d+)/i);
        if (tfr) frames = parseInt(tfr[1], 10);
      }
      if (frames && !durationMs) {
        // 优先用 Uptime - Stats since（全量统计窗口），这是最稳的没有 PROFILEDATA 时的 duration
        if (headDurationMs && headDurationMs > 200) {
          durationMs = headDurationMs;
          durationSource = 'head_uptime_minus_statssince';
        } else {
          const md = gfxRaw.match(/([0-9]+)\s*frames\s+rendered.*?over\s+([0-9]+)\s*ms/i) || null;
          if (md) { durationMs = parseInt(md[2], 10); durationSource = 'text_over_x_ms'; }
        }
        if (!fpsMethod) fpsMethod = durationSource ? `gfxinfo_totals(${durationSource})` : 'gfxinfo_totals_no_duration';
      }
      if (frames && durationMs && durationMs > 200) {
        fps = Math.max(0, Math.round((frames / (durationMs / 1000)) * 100) / 100);
      }
      // ⚠️ 兜底：只要 fpsMethod 仍为 'none'（说明 PROFILEDATA / Total frames rendered 两条路都没命中
      //         或者命中了但 duration 太短），就一律视为「未采集到可信帧」，
      //         防止 PROFILEDATA 里只有 2 帧但跨度 3 分钟 → fps=0.01 → Math.round=0 被当真实"0 FPS"。
      if (fpsMethod === 'none' || (Number.isFinite(fps) && fps <= 0 && !durationSource)) {
        fps = NaN;
      }
    } catch (e) { rawSamples.fpsErr = String(e && e.message || e).slice(0, 120); }
    if (!Number.isFinite(fps)) missing.push('fps');
  } catch (outerErr) {
    return res.json({
      success: false,
      error: (outerErr && outerErr.message) ? outerErr.message : '采集失败',
      data: null
    });
  }

  // 数值规整（保留 1 位小数，避免过长）
  const round1 = n => Number.isFinite(n) ? Math.round(n * 10) / 10 : n;
  const data = {
    cpuUsage: round1(cpuUsage),
    memUsed: round1(memUsed),
    memTotal: round1(memTotal),
    batteryTemp: round1(batteryTemp),
    fps: Number.isFinite(fps) ? Math.round(fps) : fps,
    timestamp: Date.now(),
    meta: {
      missingFields: missing,
      methods: { cpu: cpuMethod, mem: memSource, battery: batterySource, fps: fpsMethod },
      serial: effectiveSerial,
      serialInputWas: serial !== effectiveSerial ? serial : undefined,
      packageName: TV_LIVE_PACKAGE,
      rawSamples
    }
  };
  res.json({ success: true, data });
});

// ========== TVLive 播放 / 网络性能采集（WIFI ADB 直接抓） ==========
// 数据来源：
//   A. 播放性能：adb logcat -d -b main 扫描播放事件（MediaPlayer/ExoPlayer/MediaCodec/自研播放器 tag）
//        - 播放次数：onPrepared / start / setDataSource 成功回调
//        - 卡顿：MEDIA_INFO_BUFFERING_START ↔ BUFFERING_END 之间耗时；自研 tag 的 stall/buffer underrun
//        - 解码错误：MediaCodec error / onError / decode fail / codec exception
//   B. 网络性能：
//        1) TrafficStats 粒度（包名查 uid → 读 /proc/uid_stat/<uid>/tcp_(r|s)cv 或 dumpsys netstats）
//        2) Logcat HTTP 事件：请求/响应日志统计请求次数、成功失败、延迟
// 为了让前端 renderPerformance 好消化：返回字段尽量与 logs 里 playback/network 解析后的字段一致
app.post('/api/tvlive/playback-net-perf', async (req, res) => {
  const { serial } = req.body || {};
  if (!serial) return res.status(400).json({ success: false, error: 'Serial required' });

  const resolved = await resolveAdbSerial(serial);
  if (!resolved) {
    return res.status(410).json({ success: false, error: `无法匹配 serial=${serial}` });
  }
  const adbSh = (cmd, to = 8000) => adbShell(resolved, cmd, { timeout: to });

  const playback = {
    playCount: 0,
    stallCount: 0,
    decodeErrCount: 0,
    avgStallMs: 0,
    maxStallMs: 0,
    stallTimes: [],
    firstFrameMs: null,
    events: []
  };
  const network = {
    requestCount: 0,
    successCount: 0,
    errorCount: 0,
    avgLatencyMs: 0,
    maxLatencyMs: 0,
    latencies: [],
    txBytes: null,
    rxBytes: null,
    txRateKbps: null,
    rxRateKbps: null
  };
  const missing = [];
  const debug = {};

  // ------- A. 播放性能：logcat 抓取 -------
  try {
    // 优先带 tag（抓全量更耗时），失败时再兜底 logcat -d
    const tags = 'ExoPlayer:V MediaPlayer:V MediaCodec:V ACodec:V TVPlayerManager:V HuyaStreamPlayer:V TVLS:V LiveSourceLoader:V AwesomePlayer:V NuPlayer:V LibFFmpegExtractor:V RedirectHttp:V';
    let raw = await adbSh(`logcat -d -s ${tags}`, 15000);
    if (!raw || raw.trim().length < 50) {
      raw = await adbSh(`logcat -d -b main`, 15000);
    }
    // ⭐ head 不在 Android 部分 shell 里执行（低版本 toybox 无 head）；统一 Node 端裁剪
    let lines = (raw || '').split(/\r?\n/);
    if (lines.length > 5000) lines = lines.slice(0, 5000);
    debug.playbackLogLines = lines.length;

    // logcat 时间戳解析 mm-dd HH:MM:SS.mmm 或带全年的
    const parseTs = (line) => {
      const m = line.match(/(\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}[.,]\d{3})/);
      if (!m) return null;
      const s = m[1].replace(',', '.');
      const base = new Date();
      const [md, hms] = s.split(' ');
      const [mm, dd] = md.split('-');
      const [hh, mi, ssMs] = hms.split(':');
      const [ss, mss] = (ssMs || '0.000').split('.');
      base.setMonth(parseInt(mm, 10) - 1, parseInt(dd, 10));
      base.setHours(parseInt(hh, 10), parseInt(mi, 10), parseInt(ss, 10), parseInt(mss.slice(0, 3), 10));
      return base.getTime();
    };

    let bufferingStartTs = null;
    const playStartMarkers = /(onPrepared|start\(\)|setDataSource.*success|MediaPlayer.*start|startPlayback|prepared.*ok|prepared\s*$|STARTED|playStart|startPlay|EnterPlayingState|onRenderedFirstFrame|first frame|首帧)/i;
    const buffStartRe = /(BUFFERING_START|buffering.?start|buffer.?underflow|onInfo.*70[01]|MEDIA_INFO_BUFFERING_START|stall.*start|开始缓冲|卡顿时)/i;
    const buffEndRe   = /(BUFFERING_END|buffering.?end|buffer.?end|onInfo.*70[23]|MEDIA_INFO_BUFFERING_END|resume|继续播放|BUFFERING_END|卡顿结束)/i;
    const decodeErrRe = /(MediaCodec.*error|CodecException|decode.*fail|onError.*what=|MEDIA_ERROR|decode error|ACodec.*err|Codec.*Error|can't handle|硬解失败|软解失败)/i;

    for (const line of lines) {
      if (!line) continue;
      const ts = parseTs(line);
      if (playStartMarkers.test(line)) {
        playback.playCount++;
        playback.events.push({ t: ts, type: 'play', snippet: line.slice(0, 160) });
        // 首次首帧
        const firstF = line.match(/(firstFrame|first frame|首帧|onRenderedFirstFrame)[^\d]{0,6}(\d{2,6})/i);
        if (firstF && !playback.firstFrameMs) playback.firstFrameMs = parseInt(firstF[2], 10);
      }
      if (buffStartRe.test(line) && bufferingStartTs === null) {
        bufferingStartTs = ts || Date.now();
      }
      if (buffEndRe.test(line) && bufferingStartTs !== null) {
        const endTs = ts || Date.now();
        const dur = endTs - bufferingStartTs;
        if (dur >= 0 && dur < 60000) {
          playback.stallCount++;
          playback.stallTimes.push(dur);
          playback.events.push({ t: endTs, type: 'stall', duration: dur, snippet: line.slice(0, 160) });
        }
        bufferingStartTs = null;
      }
      if (decodeErrRe.test(line)) {
        playback.decodeErrCount++;
        playback.events.push({ t: ts, type: 'decodeErr', snippet: line.slice(0, 160) });
      }
    }
    if (playback.stallTimes.length > 0) {
      playback.avgStallMs = Math.round(playback.stallTimes.reduce((a, b) => a + b, 0) / playback.stallTimes.length);
      playback.maxStallMs = Math.max(...playback.stallTimes);
    }
  } catch (e) { missing.push('playback_logcat'); debug.playbackErr = String(e.message || e).slice(0, 160); }

  // ------- B. 网络性能 -------
  // B-1: logcat HTTP 请求/响应统计（RedirectHttp/OkHttp/HttpUtils/自研网络层 tag）
  try {
    const httpRaw = await adbSh(`logcat -d -s RedirectHttp:V OkHttp:V HttpUtils:V TVLS:V HuyaSDKLogger:V LiveSourceLoader:V`, 15000);
    let httpLines = (httpRaw || '').split(/\r?\n/);
    if (httpLines.length > 5000) httpLines = httpLines.slice(0, 5000);
    debug.httpLogLines = httpLines.length;
    const pendingReq = new Map(); // key(请求标识) → startTime
    const lineLatencyRe = /(latency|cost|耗时|rsp.*time)[^\d]{0,6}(\d{2,7})\s*ms/i;
    const lineUrlRe    = /(https?:\/\/[^\s"'<>]{6,120})/i;
    const lineCodeRe   = /(status|code|rsp)[=:\s]{0,3}(\d{3})/i;

    for (const l of httpLines) {
      if (!l) continue;
      const hasReq = /(GET|POST|PUT|DELETE)\s+https?:\/\//.test(l) || /(请求开始|onRequestStart|startLoad|openConnection)/i.test(l);
      if (hasReq) network.requestCount++;
      const lat = lineLatencyRe.exec(l);
      const url = lineUrlRe.exec(l);
      const code = lineCodeRe.exec(l);
      if (lat) {
        const v = parseInt(lat[2], 10);
        if (v >= 0 && v < 120000) {
          network.latencies.push(v);
          network.requestCount++; // 有耗时=请求已落地，计数+1
          if (code) {
            const c = parseInt(code[2], 10);
            if (c >= 200 && c < 400) network.successCount++; else network.errorCount++;
          } else {
            network.successCount++;
          }
        }
      } else if (code) {
        const c = parseInt(code[2], 10);
        network.requestCount++;
        if (c >= 200 && c < 400) network.successCount++; else network.errorCount++;
      }
      // 兜底：行内写了 "请求成功/失败"
      if (/(请求成功|request ok|200 OK|成功响应|加载成功|请求完成)/i.test(l) && !lat) network.successCount++;
      if (/(请求失败|request failed|异常|IO错误|timeout|timed out|连接失败|加载失败)/i.test(l)) network.errorCount++;
    }
    if (network.latencies.length > 0) {
      network.avgLatencyMs = Math.round(network.latencies.reduce((a, b) => a + b, 0) / network.latencies.length);
      network.maxLatencyMs = Math.max(...network.latencies);
    }
  } catch (e) { missing.push('network_logcat'); debug.httpErr = String(e.message || e).slice(0, 160); }

  // B-2: uid 级流量（TrafficStats）→ 读取包级累计字节，留给前端自己两次差值算速率
  try {
    // 先查包名对应 uid：❌ 不在 Android shell 里用 findstr（是 Windows 命令）！
    // 方案1 dumpsys package → Node 正则；方案2 pm list packages -U → Node 正则；方案3 pidof + /proc/<pid>/status 里 Uid:
    let uid = null;
    const pkgRaw = await adbSh(`dumpsys package ${TV_LIVE_PACKAGE}`, 6000);
    debug.pkgRawHead = (pkgRaw || '').split('\n').slice(0, 12).join(' | ').slice(0, 400);
    const uidM1 = (pkgRaw || '').match(/userId\s*[:=]\s*(\d+)/i);
    if (uidM1) uid = parseInt(uidM1[1], 10);
    if (!uid) {
      // 兜底 pm list packages -U
      try {
        const pm = await adbSh(`pm list packages -U`, 6000);
        const mPkg = (pm || '').match(new RegExp(`package:${TV_LIVE_PACKAGE.replace(/\./g, '\\.')}\\s+uid:(\\d+)`, 'i'));
        if (mPkg) uid = parseInt(mPkg[1], 10);
      } catch (_) {}
    }
    if (!uid) {
      // 兜底 pidof → /proc/<pid>/status 里的 Uid:
      try {
        const pidOut = await adbSh(`pidof ${TV_LIVE_PACKAGE}`, 4000);
        const pidM = (pidOut || '').match(/(\d+)/);
        if (pidM) {
          const status = await adbSh(`cat /proc/${pidM[1]}/status 2>/dev/null`, 4000);
          const u = (status || '').match(/^Uid:\s*(\d+)/im);
          if (u) uid = parseInt(u[1], 10);
        }
      } catch (_) {}
    }
    debug.pkgUid = uid;

    let rx = null, tx = null, trafficSource = null;

    // --- 路线1（主力 & 最精确）：dumpsys netstats detail 全量
    //     → 定位 ident=... uid=<uid> 段 → 累加段内 NetworkStatsHistory 下所有 bucket 的 rb=/tb=
    //     注意：Honor 传 "uid <uid>" 参数仍输出全量（不生效），所以必须拉全量后在 Node 端精确按 ident 分段解析
    //     之前的 proc_pid_netdev 已废弃（/proc/<pid>/net/dev 在高版本 Android 是全局命名空间视图，拿整台手机流量）
    //     ⚠️ 不再用「Stats Providers uid=xxx rxBytes= txBytes=」散行累加，
    //        因为那是 ident buckets 的汇总，再累加一次就会「翻倍甚至多倍」地虚高。
    if (uid) {
      try {
        const nsRaw = await adbSh(`dumpsys netstats detail 2>/dev/null`, 15000).catch(() => '');
        const nsLines = (nsRaw || '').split(/\r?\n/);
        const uidRe = new RegExp(`uid=${uid}(?:\\s|$|[^0-9])`);
        const bucketRe = /st=\d+\s+rb=(\d+)[^\n]*?tb=(\d+)/;

        let rxSum = 0, txSum = 0, hitIdent = 0, bucketCount = 0;
        // 状态机：inTargetBlock=true 时，bucket 行才累加；
        //         遇到新的 ident=... 行，按 uid 是否匹配切换 in/out
        let inTargetBlock = false;
        for (let i = 0; i < nsLines.length; i++) {
          const line = nsLines[i];
          // ident=... 行 （一个 block 的起始）
          if (/^\s*ident=\s*\[/.test(line) || /^\s+ident=\[/.test(line)) {
            inTargetBlock = uidRe.test(line);
            if (inTargetBlock) hitIdent++;
            continue;
          }
          // bucket 行：st=xxxx rb=xxxx rp=xxxx tb=xxxx tp=xxxx op=0
          if (inTargetBlock) {
            const bm = line.match(bucketRe);
            if (bm) {
              rxSum += parseInt(bm[1], 10) || 0;
              txSum += parseInt(bm[2], 10) || 0;
              bucketCount++;
            }
          }
        }
        if (bucketCount > 0) {
          rx = rxSum;
          tx = txSum;
          trafficSource = 'netstats_ident_bucket';
          debug.uidStats = { hitIdent, bucketCount, nsLines: nsLines.length };
        }
      } catch (_) {}
    }

    // --- 路线2（Fallback，低版本 Android）：/proc/uid_stat/<uid>/tcp_rcv / tcp_snd
    //     ⚠️ 仅在 netstats ident bucket 完全取不到值时才用；
    //        并且做 sanity check：若 uid_stat 值 > 10× 同类 APP 合理范围则丢弃（避免 Honor/鸿蒙 uid_stat 也是全局值）
    if ((rx === null || tx === null) && uid) {
      const [rxRaw, txRaw] = await Promise.all([
        adbSh(`cat /proc/uid_stat/${uid}/tcp_rcv 2>/dev/null`, 3000).catch(() => ''),
        adbSh(`cat /proc/uid_stat/${uid}/tcp_snd 2>/dev/null`, 3000).catch(() => '')
      ]);
      let fallRx = null, fallTx = null;
      if (rxRaw) {
        const n = parseInt(String(rxRaw).trim(), 10);
        if (Number.isFinite(n) && n >= 0) fallRx = n;
      }
      if (txRaw) {
        const n = parseInt(String(txRaw).trim(), 10);
        if (Number.isFinite(n) && n >= 0) fallTx = n;
      }
      // 粗略上限：单个电视 APP 一天的流量也很难超过 500GB；
      // 若 uid_stat 给出 TB 级数值，基本是厂商内核把全局值写进了 uid_stat，宁可不显示也别误导。
      const SANE_MAX = 500 * 1024 * 1024 * 1024; // 500 GB
      if (fallRx !== null && fallTx !== null && fallRx + fallTx <= SANE_MAX * 2) {
        if (rx === null) rx = fallRx;
        if (tx === null) tx = fallTx;
        if (!trafficSource) trafficSource = 'uid_stat';
      }
    }

    network.rxBytes = rx;
    network.txBytes = tx;
    network.trafficSource = trafficSource;
    // 如果 rx/tx 仍然取不到但 uid 存在，只把 uid 记入 meta（missing 不再重复加 uid）
    if (rx === null || tx === null) {
      missing.push('traffic');
    }
  } catch (e) { missing.push('traffic'); debug.trafficErr = String(e.message || e).slice(0, 160); }

  res.json({
    success: true,
    data: {
      playback,
      network,
      serial: resolved,
      serialInputWas: serial !== resolved ? serial : undefined,
      missing,
      debug,
      timestamp: Date.now()
    }
  });
});

// ========== 虎牙 API 监控端点 ==========

// 虎牙 XOR 解码辅助（与 HuyaCredentials.java / HuyaSDKParser.java 完全对齐）
//   - HuyaCredentials.XOR_KEY_STR = "90"
//   - ENCRYPTED_GAME_ID = 2426  →  decodeGameId()     = 2426 ^ 90
//   - ENCRYPTED_APP_ID  = "khinol"
//   - ENCRYPTED_APP_KEY = ">b<kci>>"
const HUYA_XOR_KEY = 90;
const HUYA_ENC_GAME_ID = 2426;
const HUYA_ENC_APP_ID  = 'khinol';
const HUYA_ENC_APP_KEY = '>b<kci>>';
function decodeHuyaString(encoded) {
  let result = '';
  for (let i = 0; i < encoded.length; i++) {
    result += String.fromCharCode(encoded.charCodeAt(i) ^ HUYA_XOR_KEY);
  }
  return result;
}
function maskAppKey(appKey) {
  if (!appKey) return '****';
  if (appKey.length <= 4) return '****';
  return appKey.substring(0, 2) + '****' + appKey.substring(appKey.length - 2);
}

// 从源码静态解码得到的默认凭证（与 APP 首次初始化/EncryptedStorage 首次写入值完全一致）
const HUYA_DEFAULT_CREDENTIALS = Object.freeze({
  gameId: HUYA_ENC_GAME_ID ^ HUYA_XOR_KEY,
  appId:  decodeHuyaString(HUYA_ENC_APP_ID),
  appKey: decodeHuyaString(HUYA_ENC_APP_KEY)
});

// 虎牙 API 历史记录
const huyaApiHistory = [];
const HUYA_HISTORY_MAX = 200;
function logHuyaApi(endpoint, method, status, duration, data) {
  const entry = {
    timestamp: Date.now(),
    endpoint, method, status, duration,
    data: data ? (typeof data === 'string' ? data.substring(0, 200) : JSON.stringify(data).substring(0, 200)) : null
  };
  huyaApiHistory.push(entry);
  if (huyaApiHistory.length > HUYA_HISTORY_MAX) {
    huyaApiHistory.shift();
  }
}

// ---- ADB 辅助：选取当前连接的第一个 device 状态 serial ----
async function pickFirstDeviceSerial() {
  // 1) 优先从 server 内存中已登记 / 已启动 logcat 的 devices map 找一个
  for (const [, dev] of devices) {
    if (dev && dev.serial) return dev.serial;
  }
  // 2) 兜底重新执行 adb devices -l，解析 state=device 的第一个
  try {
    const { stdout } = await execAsync(`"${ADB_PATH}" devices -l`, { timeout: 6000 });
    const list = parseAdbDevices(stdout);
    const alive = list.find(d => d.state === 'device');
    if (alive) return alive.serial;
  } catch (_) { /* ignore */ }
  return null;
}

// ---- ADB 辅助：对指定 serial 执行 adb shell 命令并返回 stdout ----
async function adbShell(serial, cmd, opts = {}) {
  const timeout = opts.timeout || 8000;
  try {
    const q = process.platform === 'win32' ? '"' : "'";
    // Windows: cmd 需要把引号处理干净；直接传双引号包起来整条 shell 命令
    const full = `"${ADB_PATH}" -s "${serial}" shell ${q}${cmd}${q}`;
    const { stdout, stderr } = await execAsync(full, { timeout, windowsHide: true });
    return (stdout || '') + (stderr || '');
  } catch (e) {
    // execAsync 会把非零退出码抛异常；这里仍尽量返回 stdout 供解析
    if (e && (e.stdout || e.stderr)) return (e.stdout || '') + (e.stderr || '');
    return '';
  }
}

// ---- 来源 1：adb logcat -d 抓取 HYC/HuyaSDKParser 启动日志 ----
// HuyaCredentials.initialize 打印：
//   Log.i(TAG, "从加密存储加载凭证: " + credentials.getCredentialsSummary());
// getCredentialsSummary() => "gameId=xxx, appId=ab***yz, appKey=ab***yz"
// HuyaSDKParser.init 打印：
//   Log.i(TAG, "  🔐 从加密存储加载凭证: gameId=..., appId=ab***yz, appKey=ab***yz");
// 以及：Log.i(TAG, "  🔐 使用编码后的默认凭证");
// 注意：日志里 appId/appKey 本身就是 mask 过的，因此无法拿到真实 appKey。
//       若用户在设备端通过 SettingsDialog / WebServer / Remote 更新过凭证，
//       只有 gameId 能可靠复原，appId/appKey 仍需走静态解码兜底。
async function tryExtractFromLogcat(serial) {
  if (!serial) return null;
  const out = await adbShell(serial, 'logcat -d -s HYC:V HuyaSDKParser:V MyApplication:V');
  if (!out) return null;
  // 匹配 "gameId=2336" / "gameId= 2336"
  const gidMatch = out.match(/gameId\s*[=:：]\s*(\d+)/);
  const gameId = gidMatch ? parseInt(gidMatch[1], 10) : null;
  // 匹配 "appId=12***56" 这类（只做标记用，无法复原），提示端上已经 init 过
  const hasApp = /appId\s*[=:：]\s*\S+/.test(out);
  const usedDefault = /使用编码后的默认凭证|使用默认凭证|首次初始化凭证|默认凭证已加密存储/.test(out);
  const hasCredLog = /从加密存储加载凭证|凭证初始化完成|凭证已更新|凭证已重置/.test(out);
  if (gameId == null && !hasCredLog) return null;
  return {
    gameId: Number.isFinite(gameId) && gameId > 0 ? gameId : null,
    appId: null,               // 日志为 mask，不给
    appKey: null,              // 日志为 mask，不给
    usedDefaultFallbackOnDevice: usedDefault,
    deviceInitialized: hasCredLog || hasApp,
    rawLogSnippet: out.slice(-600)
  };
}

// ---- 来源 2：adb shell 拉 SharedPreferences（encrypted_prefs.xml）----
// HuyaCredentials 对应 EncryptedStorage，SP 文件名 = "encrypted_prefs"
// 键：huya_game_id / huya_app_id / huya_app_key
// 注意：AES-256-GCM 密文，key 在 Android Keystore 无法导出，
//       拿到密文作为「设备上确实存过更新过」的证据；用于打 source 标签。
async function tryReadEncryptedPrefsXml(serial) {
  if (!serial) return null;
  const paths = [
    // 调试版 + root 可直接读
    '/data/data/com.tv.live/shared_prefs/encrypted_prefs.xml',
    // 部分厂商设备 data/data 不存在，走 sdcard 兜底路径概率极低，先跳过
  ];
  for (const p of paths) {
    // 先 run-as（debuggable APK 可用），再 cat（root 可用）
    let raw = '';
    raw = await adbShell(serial, `run-as ${TV_LIVE_PACKAGE} cat ${p} 2>/dev/null`);
    if (!raw || !raw.includes('<?xml')) {
      raw = await adbShell(serial, `cat ${p} 2>/dev/null`);
    }
    if (!raw || !raw.includes('<?xml')) continue;
    const gid = raw.match(/name="huya_game_id"[^>]*>([^<]+)</);
    const aid = raw.match(/name="huya_app_id"[^>]*>([^<]+)</);
    const akey = raw.match(/name="huya_app_key"[^>]*>([^<]+)</);
    return {
      exists: true,
      huyaGameIdCipherText: gid ? gid[1].trim() : null,   // Base64(IV):Base64(密文) AES-GCM
      huyaAppIdCipherText:  aid ? aid[1].trim() : null,
      huyaAppKeyCipherText: akey ? akey[1].trim() : null,
      note: 'ciphertext only; AES-256-GCM key locked in AndroidKeystore (不可导出)'
    };
  }
  return null;
}

// 服务器运行期凭证缓存（用户手动从 UI 点了"从设备刷新"后，把最新成功的记下来）
let huyaCachedRuntimeCredentials = null; // { gameId, appId, appKey, source, updatedAt }

// 获取虎牙凭证信息
// 优先级：
//   1) runtime 手动覆盖缓存（如果用户此前通过 update 接口写入过）
//   2) ADB logcat 抓到的 gameId（若已更新过）+ 静态 XOR 解出的 appId/appKey 做兜底合成
//   3) 源码静态 XOR 解码（与 HuyaCredentials.loadDefaultCredentials 结果完全一致）
// 返回 source 字段标明最终值的来源链
app.get('/api/huya/credentials', async (req, res) => {
  const tryDevice = (req.query.autoScan || '1') !== '0';
  const sourceChain = [];
  let gameId = HUYA_DEFAULT_CREDENTIALS.gameId;
  let appId  = HUYA_DEFAULT_CREDENTIALS.appId;
  let appKey = HUYA_DEFAULT_CREDENTIALS.appKey;
  let source = 'static_xor_decode';
  let encoded = { gameId: HUYA_ENC_GAME_ID, appId: HUYA_ENC_APP_ID, appKey: HUYA_ENC_APP_KEY };
  let logcatInfo = null;
  let prefsInfo  = null;
  let deviceSerial = null;

  // ---- Step 1：runtime 手动覆盖（最高优先级）----
  if (huyaCachedRuntimeCredentials && huyaCachedRuntimeCredentials.gameId) {
    gameId = huyaCachedRuntimeCredentials.gameId;
    appId  = huyaCachedRuntimeCredentials.appId;
    appKey = huyaCachedRuntimeCredentials.appKey;
    source = huyaCachedRuntimeCredentials.source || 'runtime_override';
    sourceChain.push('runtime_override_cache');
  } else {
    sourceChain.push('source_static_decode(base)');
  }

  // ---- Step 2：ADB 从设备端提取辅助信息 ----
  if (tryDevice) {
    try {
      deviceSerial = await pickFirstDeviceSerial();
    } catch (_) { deviceSerial = null; }
    if (deviceSerial) {
      sourceChain.push('adb_device_scan(serial=' + deviceSerial.slice(0, 16) + '...)');
      try { logcatInfo = await tryExtractFromLogcat(deviceSerial); } catch (_) { logcatInfo = null; }
      try { prefsInfo  = await tryReadEncryptedPrefsXml(deviceSerial); } catch (_) { prefsInfo  = null; }

      // 如果 logcat 中有明确 gameId 且与默认不同，说明设备端用户后来 updateCredentials 过
      if (logcatInfo && logcatInfo.gameId && logcatInfo.gameId !== HUYA_DEFAULT_CREDENTIALS.gameId) {
        if (source !== 'runtime_override_cache') {
          gameId = logcatInfo.gameId;
          source = 'adb_logcat_game_id_plus_static_appkeys';
          sourceChain.push('overridden_gameId_from_logcat');
        }
      } else if (logcatInfo && logcatInfo.usedDefaultFallbackOnDevice) {
        sourceChain.push('device_confirmed_uses_default_fallback');
      }

      if (prefsInfo && prefsInfo.exists) {
        sourceChain.push('encrypted_prefs_xml_present_on_device');
      }
    }
  }

  const updatedAt = huyaCachedRuntimeCredentials
    ? huyaCachedRuntimeCredentials.updatedAt
    : null;

  res.json({
    success: true,
    source,
    sourceChain,
    deviceSerial: deviceSerial || null,
    updatedAt,
    credentials: {
      gameId,
      appId,
      appKey: maskAppKey(appKey),
      decoded: true,
      algorithm: 'HuyaCredentials.XOR(90) fallback chain, matched to AH-main sources',
      encoded
    },
    raw: { gameId, appId, appKey },
    device: {
      logcat: logcatInfo,
      encryptedPrefs: prefsInfo
    },
    staticDefaults: {
      gameId: HUYA_DEFAULT_CREDENTIALS.gameId,
      appId:  HUYA_DEFAULT_CREDENTIALS.appId,
      appKeyMasked: maskAppKey(HUYA_DEFAULT_CREDENTIALS.appKey)
    }
  });
});

// 允许前端手动把一对最新凭证写回 server 运行期缓存（例如用户在真机上改完后，
// 手动填回 UI 后调用此接口，后续 health/parse-room 等会用这套新值）
app.post('/api/huya/credentials', express.json({ limit: '64kb' }), (req, res) => {
  const { gameId, appId, appKey, source = 'manual_override' } = req.body || {};
  if (gameId == null || !appId || !appKey) {
    res.status(400).json({ success: false, error: 'gameId/appId/appKey 必填' });
    return;
  }
  const g = Number(gameId);
  if (!Number.isFinite(g) || g <= 0) {
    res.status(400).json({ success: false, error: 'gameId 必须是正整数' });
    return;
  }
  huyaCachedRuntimeCredentials = {
    gameId: g,
    appId: String(appId),
    appKey: String(appKey),
    source,
    updatedAt: Date.now()
  };
  res.json({
    success: true,
    message: '运行期凭证已更新（仅内存保存，重启 server 后失效）',
    credentials: {
      gameId: g,
      appId: huyaCachedRuntimeCredentials.appId,
      appKey: maskAppKey(huyaCachedRuntimeCredentials.appKey)
    }
  });
});

// 辅助 endpoint：让前端点击"立即对设备执行一次源码算法复算校验"时触发
// 实际上就是 /api/huya/credentials 不带 cache 的效果；独立出来便于埋点统计
app.get('/api/huya/credentials/rescan', async (req, res) => {
  // 临时清掉 runtime 覆盖标记？不，runtime 是用户手动的，保持最高优先级。
  // 仅强制 autoScan=1 扫描设备
  req.query.autoScan = '1';
  res.redirect('/api/huya/credentials?autoScan=1');
});

// 虎牙 API 健康检查
app.get('/api/huya/health', async (req, res) => {
  const endpoints = [
    { name: '移动端解析', url: 'https://m.huya.com/1' },
    { name: 'PC网页', url: 'https://www.huya.com/' },
    { name: 'StreamInfo', url: 'https://mp.huya.com/cache.php?m=Live&do=profileRoom&roomid=1' },
    { name: 'TmpLiveList', url: 'https://live.cdn.huya.com/liveHttpUI/getTmpLiveList?iGid=2135&iTmpId=2067&iPageNo=1&iPageSize=1' },
    { name: 'CacheList', url: 'https://www.huya.com/cache.php?m=LiveList&do=getLiveListByPage&gameId=2135&tagAll=0&page=1' },
    { name: 'OpenAPI', url: 'https://open-apiext.huya.com/api/getStreamerInfo?appId=5002&roomId=1&iat=0&exp=0&sToken=invalid' }
  ];

  const results = [];
  for (const ep of endpoints) {
    const start = Date.now();
    try {
      const response = await fetch(ep.url, {
        headers: { 'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36' }
      });
      const duration = Date.now() - start;
      results.push({
        name: ep.name,
        url: ep.url,
        status: response.status,
        duration,
        ok: response.ok,
        error: null
      });
      logHuyaApi(ep.name, 'GET', response.status, duration, null);
    } catch (err) {
      const duration = Date.now() - start;
      results.push({
        name: ep.name,
        url: ep.url,
        status: 0,
        duration,
        ok: false,
        error: err.message
      });
      logHuyaApi(ep.name, 'GET', 0, duration, err.message);
    }
  }

  res.json({
    success: true,
    timestamp: Date.now(),
    results,
    summary: {
      total: results.length,
      healthy: results.filter(r => r.ok).length,
      failed: results.filter(r => !r.ok).length
    }
  });
});

// 虎牙房间解析（获取直播流地址）
app.post('/api/huya/parse-room', async (req, res) => {
  const { roomId, method = 'auto' } = req.body;
  if (!roomId) return res.status(400).json({ success: false, error: 'roomId required' });

  const startTime = Date.now();
  let result = { hlsUrl: null, flvUrl: null, source: null, rawResponse: null };

  try {
    // 方法1: 通过移动端页面解析（推荐）
    if (method === 'auto' || method === 'mobile') {
      try {
        const url = `https://m.huya.com/${roomId}`;
        const start = Date.now();
        const response = await fetch(url, {
          headers: { 'User-Agent': 'Mozilla/5.0 (Linux; Android 10; SM-G960F) AppleWebKit/537.36 Chrome/81.0.4044.138 Mobile Safari/537.36' }
        });
        const text = await response.text();
        logHuyaApi('MobilePage', 'GET', response.status, Date.now() - start, null);

        // 从页面中提取直播流地址
        const streamMatch = text.match(/"(sFlvUrl|sHlsUrl)":"([^"]+)"/);
        const streamNameMatch = text.match(/"(sStreamName|sChannel)":"([^"]+)"/);
        const suffixMatch = text.match(/"(sFlvUrlSuffix|sHlsUrlSuffix)":"([^"]+)"/);
        const antiCodeMatch = text.match(/"(sFlvAntiCode|sHlsAntiCode)":"([^"]+)"/);
        const isLiveMatch = text.match(/IS_LIVE\s*=\s*"?(\w+)"?/);

        if (streamMatch && streamNameMatch) {
          const streamUrl = streamMatch[2];
          const streamName = streamNameMatch[2];
          const suffix = suffixMatch ? suffixMatch[2] : 'flv';
          const antiCode = antiCodeMatch ? antiCodeMatch[2] : '';

          result.flvUrl = `${streamUrl}/${streamName}.${suffix}`;
          if (antiCode) {
            result.flvUrl += (antiCode.includes('?') ? '&' : '?') + antiCode;
          }
          result.source = 'MobilePage';
          result.rawResponse = { isLive: isLiveMatch ? isLiveMatch[1] : 'unknown' };
        } else {
          // 尝试从 JS 变量中提取
          const flvUrlMatch = text.match(/(https?:\/\/[^"'\s]+\.flv[^"'\s]*)/);
          const hlsUrlMatch = text.match(/(https?:\/\/[^"'\s]+\.m3u8[^"'\s]*)/);
          
          if (flvUrlMatch) {
            result.flvUrl = flvUrlMatch[1];
            result.source = 'MobilePage-Regex';
          } else if (hlsUrlMatch) {
            result.hlsUrl = hlsUrlMatch[1];
            result.source = 'MobilePage-Regex';
          }
        }
      } catch (e) {
        if (method === 'mobile') throw e;
      }
    }

    // 方法2: 通过 StreamInfo API
    if (!result.hlsUrl && !result.flvUrl && (method === 'auto' || method === 'streaminfo')) {
      try {
        const url = `https://mp.huya.com/cache.php?m=Live&do=profileRoom&roomid=${roomId}&_=${Date.now()}`;
        const start = Date.now();
        const response = await fetch(url, {
          headers: { 'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36' }
        });
        const text = await response.text();
        logHuyaApi('StreamInfo', 'GET', response.status, Date.now() - start, text);

        const json = JSON.parse(text);
        const data = json.data || {};
        const liveData = data.liveData || {};
        const streamList = liveData.stream || liveData.hybridStream || data.gameLiveInfo || [];

        if (streamList.length > 0) {
          const stream = streamList[0];
          result.flvUrl = buildStreamUrl(stream);
          result.source = 'StreamInfo';
          result.rawResponse = stream;
        }

        const iList = liveData.gameStreamInfoList || [];
        if (!result.flvUrl && iList.length > 0) {
          result.flvUrl = buildStreamUrl(iList[0]);
          result.source = 'StreamInfo';
        }
      } catch (e) {
        if (method === 'streaminfo') throw e;
      }
    }

    res.json({
      success: !!(result.hlsUrl || result.flvUrl),
      roomId,
      result,
      duration: Date.now() - startTime
    });
  } catch (err) {
    res.json({ success: false, error: err.message, duration: Date.now() - startTime });
  }
});

function buildStreamUrl(line) {
  if (!line) return null;
  const baseUrl = line.sFlvUrl || line.sHlsUrl || '';
  const streamName = line.sStreamName || '';
  const suffix = line.sFlvUrlSuffix || line.sHlsUrlSuffix || '';
  const antiCode = line.sFlvAntiCode || line.sHlsAntiCode || '';

  if (!baseUrl || !streamName) return null;

  let url = `${baseUrl}/${streamName}`;
  if (suffix) url += `.${suffix}`;
  if (antiCode) url += (antiCode.includes('?') ? '&' : '?') + antiCode;
  return url;
}

// 虎牙一起看频道列表
app.post('/api/huya/together-watch', async (req, res) => {
  const { category = 'movie', page = 1, pageSize = 20 } = req.body;

  const categoryMap = {
    movie: { tmpIds: [2067, 2069, 2071], name: '电影' },
    tv: { tmpIds: [2079, 2081, 2083], name: '剧集' },
    anime: { tmpIds: [6861, 2543, 2544], name: '动漫' },
    variety: { tmpIds: [1011, 2091], name: '综艺' },
    all: { tmpIds: [2067, 2079, 6861, 1011], name: '全部' }
  };

  const cat = categoryMap[category] || categoryMap.movie;
  const channels = [];

  try {
    for (const tmpId of cat.tmpIds) {
      const url = `https://live.cdn.huya.com/liveHttpUI/getTmpLiveList?iGid=2135&iTmpId=${tmpId}&iPageNo=${page}&iPageSize=${pageSize}`;
      const start = Date.now();
      try {
        const response = await fetch(url, {
          headers: { 'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36' }
        });
        const text = await response.text();
        logHuyaApi(`TmpLiveList-${cat.name}`, 'GET', response.status, Date.now() - start, text);

        const json = JSON.parse(text);
        const data = json.data || {};
        const list = json.vList || data.datas || data.vList || data.list || [];

        for (const item of list) {
          channels.push({
            roomId: item.lYyid || item.roomNo || item.roomId || item.lRoomId || 0,
            profileRoom: item.lProfileRoom || item.profileRoom || item.roomNo || item.lYyid || 0,
            roomName: item.sTitle || item.roomName || item.title || item.sRoomName || item.sNick || '未知',
            nickName: item.sNick || item.nick || item.nickName || item.sNickName || '未知',
            coverUrl: item.sAvatar180 || item.screenshot || item.coverUrl || item.sImgPath || '',
            onlineCount: parseInt(item.iOnlineCount || item.totalCount || item.onlineCount || '0'),
            isLive: item.iIsLive === 1 || item.isLive === 1 || item.bIsLive === 1 || parseInt(item.iOnlineCount || item.totalCount || '0') > 0,
            category: cat.name
          });
        }
      } catch (e) {
        logHuyaApi(`TmpLiveList-${cat.name}`, 'GET', 0, Date.now() - start, e.message);
      }
    }

    res.json({
      success: true,
      category: cat.name,
      channels,
      total: channels.length
    });
  } catch (err) {
    res.json({ success: false, error: err.message });
  }
});

// 虎牙 Cache API 频道列表
app.post('/api/huya/cache-list', async (req, res) => {
  const { gameId = 2135, page = 1 } = req.body;

  try {
    const url = `https://www.huya.com/cache.php?m=LiveList&do=getLiveListByPage&gameId=${gameId}&tagAll=0&page=${page}`;
    const start = Date.now();
    const response = await fetch(url, {
      headers: { 'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36' }
    });
    const text = await response.text();
    logHuyaApi('CacheList', 'GET', response.status, Date.now() - start, text);

    const json = JSON.parse(text);
    const data = json.data || {};
    const channels = (data.datas || []).map(item => ({
      roomId: item.roomNo || item.roomId || 0,
      roomName: item.roomName || '未知',
      nickName: item.nick || '未知',
      coverUrl: item.screenshot || '',
      onlineCount: parseInt(item.totalCount || '0'),
      isLive: item.isLive === 1
    }));

    res.json({ success: true, channels, total: channels.length, raw: data });
  } catch (err) {
    res.json({ success: false, error: err.message });
  }
});

// 虎牙 API 历史记录
app.get('/api/huya/history', (req, res) => {
  const limit = parseInt(req.query.limit) || 50;
  const history = huyaApiHistory.slice(-limit).reverse();

  const stats = {
    total: huyaApiHistory.length,
    byEndpoint: {},
    avgDuration: 0,
    errorRate: 0
  };

  let totalDuration = 0;
  let errorCount = 0;
  for (const entry of huyaApiHistory) {
    if (!stats.byEndpoint[entry.endpoint]) {
      stats.byEndpoint[entry.endpoint] = { calls: 0, errors: 0, totalDuration: 0 };
    }
    stats.byEndpoint[entry.endpoint].calls++;
    stats.byEndpoint[entry.endpoint].totalDuration += entry.duration;
    totalDuration += entry.duration;
    if (entry.status === 0 || entry.status >= 400) {
      stats.byEndpoint[entry.endpoint].errors++;
      errorCount++;
    }
  }

  for (const ep in stats.byEndpoint) {
    stats.byEndpoint[ep].avgDuration = Math.round(stats.byEndpoint[ep].totalDuration / stats.byEndpoint[ep].calls);
    stats.byEndpoint[ep].errorRate = Math.round((stats.byEndpoint[ep].errors / stats.byEndpoint[ep].calls) * 100);
  }

  if (huyaApiHistory.length > 0) {
    stats.avgDuration = Math.round(totalDuration / huyaApiHistory.length);
    stats.errorRate = Math.round((errorCount / huyaApiHistory.length) * 100);
  }

  res.json({ success: true, history, stats });
});

// 清除虎牙 API 历史
app.delete('/api/huya/history', (req, res) => {
  huyaApiHistory.length = 0;
  res.json({ success: true, message: '历史记录已清除' });
});

// 虎牙域名 DNS 解析
app.get('/api/huya/dns', async (req, res) => {
  const domains = [
    'live-api.huya.com',
    'www.huya.com',
    'm.huya.com',
    'mp.huya.com',
    'live.cdn.huya.com',
    'cdn.huya.com'
  ];

  const results = [];
  for (const domain of domains) {
    const start = Date.now();
    try {
      const response = await fetch(`https://${domain}`, {
        method: 'HEAD',
        headers: { 'User-Agent': 'Mozilla/5.0' }
      });
      results.push({
        domain,
        reachable: true,
        status: response.status,
        duration: Date.now() - start
      });
    } catch (err) {
      results.push({
        domain,
        reachable: false,
        status: 0,
        duration: Date.now() - start,
        error: err.message
      });
    }
  }

  res.json({ success: true, results });
});

// 虎牙 CDN 节点检测
app.get('/api/huya/cdn-check', async (req, res) => {
  const cdnHosts = [
    'cdn.huya.com',
    'hls.huya.com',
    'flv.huya.com',
    'p2p.huya.com'
  ];

  const results = [];
  for (const host of cdnHosts) {
    const start = Date.now();
    try {
      const response = await fetch(`https://${host}`, {
        method: 'HEAD',
        headers: { 'User-Agent': 'Mozilla/5.0' }
      });
      results.push({
        host,
        reachable: true,
        status: response.status,
        duration: Date.now() - start
      });
    } catch (err) {
      results.push({
        host,
        reachable: false,
        duration: Date.now() - start,
        error: err.message
      });
    }
  }

  res.json({ success: true, results });
});

app.get('/api/devices', (req, res) => {
  const deviceList = [];
  for (const [id, device] of devices) {
    deviceList.push({
      id: device.id,
      info: device.info,
      connected: device.ws && device.ws.readyState === WebSocket.OPEN,
      lastSeen: device.lastSeen
    });
  }
  res.json({ devices: deviceList, count: deviceList.length });
});

app.get('/api/logs', (req, res) => {
  const { deviceId, type, limit = 100 } = req.query;
  let filtered = logs;
  if (deviceId) {
    filtered = filtered.filter(l => l.deviceId === deviceId);
  }
  if (type) {
    filtered = filtered.filter(l => l.logType === type);
  }
  res.json({ logs: filtered.slice(-parseInt(limit)), total: filtered.length });
});

app.post('/api/device/connect', async (req, res) => {
  const { ip, port = 9527, token } = req.body;
  if (!ip) {
    return res.status(400).json({ success: false, error: 'IP address is required' });
  }

  try {
    const wsUrl = `ws://${ip}:${port}`;
    const deviceWs = new WebSocket(wsUrl, {
      headers: { 'User-Agent': 'TVLiveLogMonitor/1.0' }
    });

    const deviceId = `device_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;

    deviceWs.on('open', () => {
      const initMessage = {
        type: 'subscribe',
        token: token || ''
      };
      deviceWs.send(JSON.stringify(initMessage));

      devices.set(deviceId, {
        id: deviceId,
        ws: deviceWs,
        info: { ip, port },
        lastSeen: Date.now(),
        reconnectAttempts: 0
      });

      broadcastToClients({
        type: 'device_connected',
        device: { id: deviceId, ip, port }
      });

      res.json({ success: true, deviceId, message: 'Device connected' });
    });

    deviceWs.on('message', (data) => {
      try {
        const message = JSON.parse(data.toString());
        handleDeviceMessage(deviceId, message);
      } catch (e) {
        console.error('Error parsing device message:', e);
      }
    });

    deviceWs.on('error', (err) => {
      console.error(`Device ${deviceId} error:`, err.message);
      broadcastToClients({
        type: 'device_error',
        deviceId,
        error: err.message
      });
      if (!res.headersSent) {
        res.status(500).json({ success: false, error: err.message });
      }
    });

    deviceWs.on('close', () => {
      const device = devices.get(deviceId);
      if (device) {
        broadcastToClients({
          type: 'device_disconnected',
          deviceId
        });
        devices.delete(deviceId);
      }
    });

    setTimeout(() => {
      if (!res.headersSent) {
        res.status(504).json({ success: false, error: 'Connection timeout' });
      }
    }, 10000);

  } catch (error) {
    res.status(500).json({ success: false, error: error.message });
  }
});

app.post('/api/device/disconnect', (req, res) => {
  const { deviceId } = req.body;
  const device = devices.get(deviceId);
  if (device) {
    try {
      device.ws.close();
    } catch (e) {}
    devices.delete(deviceId);
    broadcastToClients({ type: 'device_disconnected', deviceId });
    res.json({ success: true });
  } else {
    res.status(404).json({ success: false, error: 'Device not found' });
  }
});

app.post('/api/logs/clear', (req, res) => {
  flushLogBatch();
  logs.length = 0;
  broadcastToClients({ type: 'logs_cleared' });
  res.json({ success: true });
});

app.get('/api/scan/devices', async (req, res) => {
  const port = parseInt(req.query.port) || 9527;
  const subnet = req.query.subnet || '';
  const discovered = [];
  
  try {
    const localIPs = getLocalIPs();
    const scanTargets = [];
    
    if (subnet) {
      for (let i = 1; i <= 254; i++) {
        scanTargets.push(`${subnet}.${i}`);
      }
    } else {
      for (const ip of localIPs) {
        const parts = ip.split('.');
        if (parts.length === 4 && parts[0] !== '127') {
          const subnet = `${parts[0]}.${parts[1]}.${parts[2]}`;
          for (let i = 1; i <= 254; i++) {
            if (`${subnet}.${i}` !== ip) {
              scanTargets.push(`${subnet}.${i}`);
            }
          }
        }
      }
    }

    const results = await Promise.allSettled(
      scanTargets.map(ip => probeDevice(ip, port))
    );
    
    results.forEach((result, index) => {
      if (result.status === 'fulfilled' && result.value) {
        discovered.push(result.value);
      }
    });

    broadcastToClients({ type: 'scan_results', devices: discovered });
    res.json({ success: true, devices: discovered, scanCount: discovered.length });
  } catch (error) {
    res.status(500).json({ success: false, error: error.message });
  }
});

function getLocalIPs() {
  const interfaces = os.networkInterfaces();
  const ips = [];
  
  for (const name of Object.keys(interfaces)) {
    for (const iface of interfaces[name]) {
      if (iface.family === 'IPv4' && !iface.internal) {
        ips.push(iface.address);
      }
    }
  }
  
  if (ips.length === 0) {
    ips.push('127.0.0.1');
  }
  
  return ips;
}

function probeDevice(ip, port) {
  return new Promise((resolve) => {
    const timeout = setTimeout(() => resolve(null), 800);
    
    const req = http.request({
      hostname: ip,
      port: port,
      path: '/api/info',
      method: 'GET',
      timeout: 700
    }, (res) => {
      clearTimeout(timeout);
      let data = '';
      res.on('data', chunk => data += chunk);
      res.on('end', () => {
        try {
          const info = JSON.parse(data);
          resolve({
            ip: ip,
            port: port,
            deviceName: info.deviceName || 'Unknown Device',
            deviceModel: info.deviceModel || 'Unknown',
            appVersion: info.appVersion || 'Unknown',
            sdkVersion: info.sdkVersion,
            androidVersion: info.androidVersion,
            brand: info.brand,
            manufacturer: info.manufacturer,
            deviceId: info.deviceId
          });
        } catch (e) {
          resolve({ ip: ip, port: port, deviceName: 'TV Live Device', deviceModel: '', appVersion: '' });
        }
      });
    });
    
    req.on('error', () => {
      clearTimeout(timeout);
      resolve(null);
    });
    
    req.on('timeout', () => {
      clearTimeout(timeout);
      req.destroy();
      resolve(null);
    });
    
    req.end();
  });
}

app.post('/api/scan/connect', async (req, res) => {
  const { ip, port = 9527 } = req.body;
  if (!ip) {
    return res.status(400).json({ success: false, error: 'IP address is required' });
  }

  try {
    const deviceId = `device_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
    const wsUrl = `ws://${ip}:${port}`;
    const deviceWs = new WebSocket(wsUrl);

    deviceWs.on('open', () => {
      deviceWs.send(JSON.stringify({ type: 'subscribe', token: '' }));
      devices.set(deviceId, {
        id: deviceId,
        ws: deviceWs,
        info: { ip, port },
        lastSeen: Date.now(),
        reconnectAttempts: 0
      });
      broadcastToClients({ type: 'device_connected', device: { id: deviceId, ip, port } });
      res.json({ success: true, deviceId });
    });

    deviceWs.on('message', (data) => {
      try {
        const message = JSON.parse(data.toString());
        handleDeviceMessage(deviceId, message);
      } catch (e) {}
    });

    deviceWs.on('error', () => {
      if (!res.headersSent) {
        res.status(500).json({ success: false, error: '连接失败' });
      }
    });

    deviceWs.on('close', () => {
      const device = devices.get(deviceId);
      if (device) {
        broadcastToClients({ type: 'device_disconnected', deviceId });
        devices.delete(deviceId);
      }
    });

    setTimeout(() => {
      if (!res.headersSent) {
        res.status(504).json({ success: false, error: '连接超时' });
      }
    }, 5000);
  } catch (error) {
    res.status(500).json({ success: false, error: error.message });
  }
});

app.get('/api/logs/export', (req, res) => {
  const { deviceId, type } = req.query;
  let filtered = logs;
  if (deviceId) filtered = filtered.filter(l => l.deviceId === deviceId);
  if (type) filtered = filtered.filter(l => l.logType === type);

  if (filtered.length === 0) {
    return res.json({ 
      exportedAt: new Date().toISOString(), 
      totalLogs: 0, 
      warning: '服务器端日志为空，请使用客户端导出',
      logs: [] 
    });
  }

  const exportData = {
    exportedAt: new Date().toISOString(),
    totalLogs: filtered.length,
    logs: filtered
  };

  res.setHeader('Content-Type', 'application/json');
  res.setHeader('Content-Disposition', `attachment; filename=logs_${Date.now()}.json`);
  res.send(JSON.stringify(exportData, null, 2));
});

app.post('/api/logs/export', (req, res) => {
  try {
    const { logs: clientLogs, format = 'json' } = req.body;
    if (!clientLogs || !Array.isArray(clientLogs)) {
      return res.status(400).json({ success: false, error: '无效的日志数据' });
    }

    const timestamp = new Date().toISOString().replace(/[:.]/g, '-');
    if (format === 'csv') {
      const headers = ['时间', '设备', '类型', '标签', '消息'];
      const rows = [headers.join(',')];
      clientLogs.forEach(log => {
        const row = [
          log.timestamp ? new Date(log.timestamp).toISOString() : '',
          log.deviceId || '',
          log.logType || log.type || '',
          log.tag || '',
          (log.message || log.stackTrace || '').replace(/"/g, '""')
        ];
        rows.push(row.map(cell => `"${cell}"`).join(','));
      });
      const csvContent = '\uFEFF' + rows.join('\n');
      res.setHeader('Content-Type', 'text/csv;charset=utf-8');
      res.setHeader('Content-Disposition', `attachment; filename=logs_${timestamp}.csv`);
      return res.send(csvContent);
    }

    const exportData = {
      exportedAt: new Date().toISOString(),
      totalLogs: clientLogs.length,
      logs: clientLogs
    };
    res.setHeader('Content-Type', 'application/json');
    res.setHeader('Content-Disposition', `attachment; filename=logs_${timestamp}.json`);
    res.send(JSON.stringify(exportData, null, 2));
  } catch (err) {
    res.status(500).json({ success: false, error: err.message });
  }
});

function handleDeviceMessage(deviceId, message) {
  const device = devices.get(deviceId);
  if (!device) return;

  device.lastSeen = Date.now();

  switch (message.type) {
    case 'init':
      device.info = message.device || device.info;
      device.info.ip = device.info.ip || 'unknown';
      broadcastToClients({ type: 'device_init', deviceId, deviceInfo: message.device });
      break;

    case 'log':
    case 'crash':
    case 'batch_logs':
      if (message.type === 'batch_logs' && Array.isArray(message.logs)) {
        message.logs.forEach(log => addLog(deviceId, log));
      } else {
        addLog(deviceId, message);
      }
      break;

    case 'pong':
      break;

    case 'logs_cleared':
      broadcastToClients({ type: 'device_logs_cleared', deviceId });
      break;

    case 'error':
      console.error('Device error:', message.message);
      break;
  }
}

function addLog(deviceId, logEntry) {
  const log = {
    ...logEntry,
    deviceId,
    serverTime: new Date().toISOString()
  };

  logs.push(log);
  if (logs.length > MAX_LOGS) {
    logs.shift();
  }

  logBatchBuffer.push(log);
  if (logBatchBuffer.length >= LOG_BATCH_MAX_SIZE) {
    flushLogBatch();
  } else if (!logBatchTimer) {
    logBatchTimer = setTimeout(flushLogBatch, LOG_BATCH_INTERVAL);
  }
}

function flushLogBatch() {
  if (logBatchTimer) {
    clearTimeout(logBatchTimer);
    logBatchTimer = null;
  }
  if (logBatchBuffer.length === 0) return;
  
  const batch = logBatchBuffer.splice(0, logBatchBuffer.length);
  broadcastToClients({ type: 'logs_batch', logs: batch });
}

function broadcastToClients(message) {
  const data = JSON.stringify(message);
  for (const client of clientConnections) {
    if (client.readyState === WebSocket.OPEN) {
      try {
        client.send(data);
      } catch (e) {}
    }
  }
}

function broadcastDeviceList() {
  const deviceList = [];
  for (const [id, device] of devices) {
    deviceList.push({
      id: device.id,
      info: device.info,
      connected: device.connected !== false
    });
  }
  broadcastToClients({ type: 'devices_list', devices: deviceList });
}

wss.on('connection', (ws) => {
  clientConnections.add(ws);

  ws.send(JSON.stringify({
    type: 'connected',
    message: 'Connected to TV Live Log Monitor',
    serverTime: new Date().toISOString()
  }));

  const deviceList = [];
  for (const [id, device] of devices) {
    deviceList.push({
      id: device.id,
      info: device.info,
      connected: device.ws && device.ws.readyState === WebSocket.OPEN
    });
  }
  ws.send(JSON.stringify({ type: 'devices_list', devices: deviceList }));

  const recentLogs = logs.slice(-200);
  ws.send(JSON.stringify({ type: 'logs', logs: recentLogs }));

  ws.on('message', (data) => {
    try {
      const message = JSON.parse(data.toString());
      handleClientMessage(ws, message);
    } catch (e) {}
  });

  ws.on('close', () => {
    clientConnections.delete(ws);
  });
});

function handleClientMessage(ws, message) {
  switch (message.type) {
    case 'ping':
      ws.send(JSON.stringify({ type: 'pong', timestamp: Date.now() }));
      break;

    case 'get_devices':
      const deviceList = [];
      for (const [id, device] of devices) {
        deviceList.push({
          id: device.id,
          info: device.info,
          connected: device.ws && device.ws.readyState === WebSocket.OPEN
        });
      }
      ws.send(JSON.stringify({ type: 'devices_list', devices: deviceList }));
      break;

    case 'get_logs':
      const { deviceId, limit = 200 } = message;
      let filtered = logs;
      if (deviceId) filtered = filtered.filter(l => l.deviceId === deviceId);
      ws.send(JSON.stringify({ type: 'logs', logs: filtered.slice(-limit) }));
      break;

    case 'clear_logs':
      logs.length = 0;
      broadcastToClients({ type: 'logs_cleared' });
      break;

    case 'device_action':
      const { action, deviceId: targetDeviceId, payload } = message;
      const deviceTarget = devices.get(targetDeviceId);
      if (deviceTarget && deviceTarget.ws && deviceTarget.ws.readyState === WebSocket.OPEN) {
        deviceTarget.ws.send(JSON.stringify({ type: action, ...payload }));
      }
      break;
  }
}

// ADB 设备心跳检查 - 每 30 秒检查一次 ADB 设备连接状态
setInterval(() => {
  const adbDevices = [];
  for (const [id, device] of devices) {
    if (device.type === 'adb' && device.adbSerial) {
      adbDevices.push({ id, serial: device.adbSerial, device });
    }
  }
  
  if (adbDevices.length === 0) return;
  
  console.log(`[ADB Heartbeat] Checking ${adbDevices.length} ADB device(s)...`);
  
  exec(`"${ADB_PATH}" devices`, (err, stdout) => {
    if (err) {
      console.error(`[ADB Heartbeat] Failed to check devices: ${err.message}`);
      return;
    }
    
    for (const { id, serial, device } of adbDevices) {
      // 查找该设备对应的 session
      let session = null;
      let sessionKey = null;
      for (const [sKey, s] of adbSessions) {
        if (s.id === id) {
          session = s;
          sessionKey = sKey;
          break;
        }
      }
      
      if (stdout.includes(serial)) {
        // 设备仍连接
        if (session) {
          // 检查 logcat 进程是否仍在运行
          if (session.process && (session.process.killed || session.process.exitCode !== null)) {
            console.log(`[ADB Heartbeat] Logcat process died for ${serial}, restarting...`);
            const adb = `"${ADB_PATH}" -s "${serial}"`;
            const newSessionId = sessionKey || id;
            startLogcatByTags(adb, serial, id, newSessionId);
          }
        }
      } else {
        // 设备已断开
        console.log(`[ADB Heartbeat] Device ${serial} disconnected!`);
        if (session && !session.manualDisconnect) {
          // 不是手动断开，标记设备断开
          device.connected = false;
          broadcastToClients({ type: 'device_disconnected', deviceId: id });
          broadcastDeviceList();
        }
      }
    }
  });
}, 30000);

setInterval(() => {
  const now = Date.now();
  for (const [id, device] of devices) {
    // 只检查有 WebSocket 的设备（网络连接模式），ADB设备不需要超时检查
    if (device.ws && device.ws.readyState === WebSocket.OPEN) {
      if (now - device.lastSeen > 30000) {
        try { device.ws.close(); } catch (e) {}
        devices.delete(id);
        broadcastToClients({ type: 'device_timeout', deviceId: id });
      }
    }
  }
}, 10000);

// ========== 云端模式支持 ==========

const cloudConfig = {
  url: '',
  apiKey: '',
  connected: false,
  ws: null,
  pollingInterval: null,
  lastSyncTime: 0
};

/**
 * 连接云端服务器
 */
app.post('/api/cloud/connect', async (req, res) => {
  const { url, apiKey } = req.body;
  
  if (!url) {
    return res.status(400).json({ success: false, error: '云端服务器地址不能为空' });
  }
  
  try {
    // 先测试 HTTP 连接
    const statusCheck = await new Promise((resolve, reject) => {
      const httpClient = url.startsWith('https') ? https : http;
      const req = httpClient.request({
        hostname: new URL(url).hostname,
        port: new URL(url).port || (url.startsWith('https') ? 443 : 80),
        path: '/api/status',
        method: 'GET',
        timeout: 5000
      }, (response) => {
        let data = '';
        response.on('data', chunk => data += chunk);
        response.on('end', () => {
          try {
            resolve(JSON.parse(data));
          } catch {
            resolve({ status: 'unknown' });
          }
        });
      });
      
      req.on('error', reject);
      req.on('timeout', () => {
        req.destroy();
        reject(new Error('连接超时'));
      });
      
      req.end();
    });
    
    if (!statusCheck || statusCheck.status !== 'ok') {
      return res.status(400).json({ success: false, error: '无法连接到云端服务器' });
    }
    
    // 保存配置
    cloudConfig.url = url;
    cloudConfig.apiKey = apiKey || '';
    cloudConfig.connected = true;
    
    // 建立 WebSocket 连接
    const wsUrl = url.replace('http://', 'ws://').replace('https://', 'wss://') + '/ws';
    const wsQuery = cloudConfig.apiKey ? `?apiKey=${encodeURIComponent(cloudConfig.apiKey)}` : '';
    
    cloudConfig.ws = new WebSocket(wsUrl + wsQuery);
    
    cloudConfig.ws.on('open', () => {
      console.log('[Cloud] WebSocket connected');
      // 获取历史日志
      fetchCloudLogs();
      startCloudPolling();
      
      broadcastToClients({
        type: 'cloud_connected',
        serverUrl: url
      });
    });
    
    cloudConfig.ws.on('message', (data) => {
      try {
        const message = JSON.parse(data.toString());
        handleCloudMessage(message);
      } catch (e) {
        console.error('[Cloud] Message parse error:', e);
      }
    });
    
    cloudConfig.ws.on('close', () => {
      console.log('[Cloud] WebSocket disconnected');
      cloudConfig.connected = false;
      stopCloudPolling();
      
      broadcastToClients({
        type: 'cloud_disconnected'
      });
      
      // 自动重连
      setTimeout(() => {
        if (cloudConfig.connected || cloudConfig.url) {
          reconnectCloud();
        }
      }, 5000);
    });
    
    cloudConfig.ws.on('error', (err) => {
      console.error('[Cloud] WebSocket error:', err.message);
    });
    
    res.json({ success: true, message: '已连接到云端服务器', serverInfo: statusCheck });
    
  } catch (error) {
    cloudConfig.connected = false;
    res.status(500).json({ success: false, error: error.message });
  }
});

/**
 * 断开云端服务器
 */
app.post('/api/cloud/disconnect', (req, res) => {
  cloudConfig.connected = false;
  stopCloudPolling();
  
  if (cloudConfig.ws) {
    try {
      cloudConfig.ws.close(1000, 'Client disconnecting');
    } catch (e) {}
    cloudConfig.ws = null;
  }
  
  broadcastToClients({ type: 'cloud_disconnected' });
  res.json({ success: true });
});

/**
 * 获取云端状态
 */
app.get('/api/cloud/status', (req, res) => {
  res.json({
    connected: cloudConfig.connected,
    serverUrl: cloudConfig.url,
    hasApiKey: !!cloudConfig.apiKey,
    lastSyncTime: cloudConfig.lastSyncTime,
    pendingLogs: logs.length
  });
});

/**
 * 获取云端设备列表
 */
app.get('/api/cloud/devices', async (req, res) => {
  if (!cloudConfig.connected || !cloudConfig.url) {
    return res.status(400).json({ success: false, error: '未连接到云端服务器' });
  }
  
  try {
    const httpClient = cloudConfig.url.startsWith('https') ? https : http;
    const reqOptions = {
      hostname: new URL(cloudConfig.url).hostname,
      port: new URL(cloudConfig.url).port || (cloudConfig.url.startsWith('https') ? 443 : 80),
      path: '/api/devices',
      method: 'GET',
      timeout: 10000
    };
    
    if (cloudConfig.apiKey) {
      reqOptions.headers = { 'x-api-key': cloudConfig.apiKey };
    }
    
    const response = await new Promise((resolve, reject) => {
      const req = httpClient.request(reqOptions, (res) => {
        let data = '';
        res.on('data', chunk => data += chunk);
        res.on('end', () => {
          resolve({ statusCode: res.statusCode, body: JSON.parse(data) });
        });
      });
      req.on('error', reject);
      req.end();
    });
    
    res.json(response.body);
  } catch (error) {
    res.status(500).json({ success: false, error: error.message });
  }
});

/**
 * 获取云端日志
 */
app.get('/api/cloud/logs', async (req, res) => {
  if (!cloudConfig.connected || !cloudConfig.url) {
    return res.status(400).json({ success: false, error: '未连接到云端服务器' });
  }
  
  const { deviceId, limit = 500, type } = req.query;
  
  try {
    let path = `/api/logs?limit=${limit}`;
    if (deviceId) path += `&deviceId=${deviceId}`;
    if (type) path += `&type=${type}`;
    
    const httpClient = cloudConfig.url.startsWith('https') ? https : http;
    const reqOptions = {
      hostname: new URL(cloudConfig.url).hostname,
      port: new URL(cloudConfig.url).port || (cloudConfig.url.startsWith('https') ? 443 : 80),
      path,
      method: 'GET',
      timeout: 10000
    };
    
    if (cloudConfig.apiKey) {
      reqOptions.headers = { 'x-api-key': cloudConfig.apiKey };
    }
    
    const response = await new Promise((resolve, reject) => {
      const req = httpClient.request(reqOptions, (res) => {
        let data = '';
        res.on('data', chunk => data += chunk);
        res.on('end', () => {
          resolve({ statusCode: res.statusCode, body: JSON.parse(data) });
        });
      });
      req.on('error', reject);
      req.end();
    });
    
    // 将云端日志添加到本地日志存储
    if (response.body && response.body.logs) {
      for (const log of response.body.logs) {
        addLog(log.deviceId || 'cloud_' + Date.now(), log);
      }
    }
    
    res.json(response.body);
  } catch (error) {
    res.status(500).json({ success: false, error: error.message });
  }
});

/**
 * 清空云端日志
 */
app.delete('/api/cloud/logs', async (req, res) => {
  if (!cloudConfig.connected || !cloudConfig.url) {
    return res.status(400).json({ success: false, error: '未连接到云端服务器' });
  }
  
  const { deviceId } = req.query;
  const path = deviceId ? `/api/logs/${deviceId}` : '/api/logs';
  
  try {
    const httpClient = cloudConfig.url.startsWith('https') ? https : http;
    const reqOptions = {
      hostname: new URL(cloudConfig.url).hostname,
      port: new URL(cloudConfig.url).port || (cloudConfig.url.startsWith('https') ? 443 : 80),
      path,
      method: 'DELETE',
      timeout: 10000
    };
    
    if (cloudConfig.apiKey) {
      reqOptions.headers = { 'x-api-key': cloudConfig.apiKey };
    }
    
    const response = await new Promise((resolve, reject) => {
      const req = httpClient.request(reqOptions, (res) => {
        let data = '';
        res.on('data', chunk => data += chunk);
        res.on('end', () => {
          resolve({ statusCode: res.statusCode, body: data ? JSON.parse(data) : {} });
        });
      });
      req.on('error', reject);
      req.end();
    });
    
    res.json(response.body || { success: true });
  } catch (error) {
    res.status(500).json({ success: false, error: error.message });
  }
});

/**
 * 处理云端消息
 */
function handleCloudMessage(message) {
  switch (message.type) {
    case 'logs_batch':
      if (message.logs && Array.isArray(message.logs)) {
        for (const log of message.logs) {
          addLog(message.deviceId || log.deviceId || 'unknown', log);
        }
      }
      break;
      
    case 'history_logs':
      if (message.logs && Array.isArray(message.logs)) {
        for (const log of message.logs) {
          addLog(log.deviceId || 'unknown', log);
        }
      }
      break;
      
    case 'log':
      addLog(message.deviceId || 'unknown', message);
      break;
      
    case 'devices_list':
      broadcastToClients({
        type: 'cloud_devices_list',
        devices: message.devices
      });
      break;
      
    case 'logs_cleared':
      broadcastToClients({
        type: 'cloud_logs_cleared',
        deviceId: message.deviceId
      });
      break;
      
    case 'all_logs_cleared':
      logs.length = 0;
      broadcastToClients({ type: 'logs_cleared' });
      break;
  }
}

/**
 * 从云端获取日志
 */
async function fetchCloudLogs() {
  if (!cloudConfig.connected || !cloudConfig.url) return;
  
  try {
    const httpClient = cloudConfig.url.startsWith('https') ? https : http;
    const reqOptions = {
      hostname: new URL(cloudConfig.url).hostname,
      port: new URL(cloudConfig.url).port || (cloudConfig.url.startsWith('https') ? 443 : 80),
      path: '/api/logs?limit=100',
      method: 'GET',
      timeout: 10000
    };
    
    if (cloudConfig.apiKey) {
      reqOptions.headers = { 'x-api-key': cloudConfig.apiKey };
    }
    
    const response = await new Promise((resolve, reject) => {
      const req = httpClient.request(reqOptions, (res) => {
        let data = '';
        res.on('data', chunk => data += chunk);
        res.on('end', () => {
          try {
            resolve(JSON.parse(data));
          } catch {
            resolve({ logs: [] });
          }
        });
      });
      req.on('error', reject);
      req.end();
    });
    
    if (response.logs && Array.isArray(response.logs)) {
      for (const log of response.logs) {
        addLog(log.deviceId || 'cloud', log);
      }
    }
    
    cloudConfig.lastSyncTime = Date.now();
  } catch (e) {
    console.error('[Cloud] Failed to fetch logs:', e.message);
  }
}

/**
 * 启动云端轮询
 */
function startCloudPolling() {
  stopCloudPolling();
  
  cloudConfig.pollingInterval = setInterval(() => {
    if (cloudConfig.connected) {
      fetchCloudLogs();
    }
  }, 5000); // 每5秒同步一次
}

/**
 * 停止云端轮询
 */
function stopCloudPolling() {
  if (cloudConfig.pollingInterval) {
    clearInterval(cloudConfig.pollingInterval);
    cloudConfig.pollingInterval = null;
  }
}

/**
 * 重连云端
 */
function reconnectCloud() {
  if (cloudConfig.connected || !cloudConfig.url) return;
  
  console.log('[Cloud] Reconnecting...');
  
  const wsUrl = cloudConfig.url.replace('http://', 'ws://').replace('https://', 'wss://') + '/ws';
  const wsQuery = cloudConfig.apiKey ? `?apiKey=${encodeURIComponent(cloudConfig.apiKey)}` : '';
  
  try {
    cloudConfig.ws = new WebSocket(wsUrl + wsQuery);
    
    cloudConfig.ws.on('open', () => {
      console.log('[Cloud] Reconnected');
      cloudConfig.connected = true;
      fetchCloudLogs();
      startCloudPolling();
      broadcastToClients({ type: 'cloud_connected', serverUrl: cloudConfig.url });
    });
    
    cloudConfig.ws.on('message', (data) => {
      try {
        const message = JSON.parse(data.toString());
        handleCloudMessage(message);
      } catch (e) {}
    });
    
    cloudConfig.ws.on('close', () => {
      cloudConfig.connected = false;
      setTimeout(reconnectCloud, 5000);
    });
    
    cloudConfig.ws.on('error', () => {
      cloudConfig.connected = false;
      setTimeout(reconnectCloud, 5000);
    });
    
  } catch (e) {
    console.error('[Cloud] Reconnect failed:', e.message);
    setTimeout(reconnectCloud, 10000);
  }
}

// 修改服务器启动信息，添加云端模式提示
const originalListen = server.listen.bind(server);
server.listen(PORT, '0.0.0.0', () => {
  console.log(`
╔══════════════════════════════════════════════════════════════╗
║           TV Live Log Monitor Server                         ║
╠══════════════════════════════════════════════════════════════╣
║  📊 Web UI:  http://localhost:${PORT}                              ║
║  🔌 API:     http://localhost:${PORT}/api/devices                ║
║  📡 Logs:    http://localhost:${PORT}/api/logs                   ║
║  💾 Export:  http://localhost:${PORT}/api/logs/export            ║
║  ☁️  Cloud:   http://localhost:${PORT}/api/cloud/connect         ║
╠══════════════════════════════════════════════════════════════╣
║  Connected devices: ${devices.size.toString().padStart(3, ' ')}                                    ║
║  Logs buffer:      ${logs.length.toString().padStart(4, ' ')} / ${MAX_LOGS}                            ║
║  Cloud status:    ${cloudConfig.connected ? '✅ Connected' : '❌ Disconnected'.padStart(20 - (cloudConfig.connected ? 0 : 4), ' ')}                    ║
╚══════════════════════════════════════════════════════════════╝
  `);
});
