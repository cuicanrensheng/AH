let ws = null;
let devices = new Map();
let logs = [];
let currentView = 'logs';
let autoScroll = true;
let pauseUpdates = false;
let autoClearOnDisconnect = true;
let filterSystemLogs = false;
let adbAvailable = false;
let discoveredDevices = [];
const startTime = Date.now();

const MAX_VISIBLE_LOGS = 500;
const MAX_LOGS = 100000;
const RENDER_CHUNK_SIZE = 100;
let pendingLogs = [];
let renderScheduled = false;
let lastRenderTime = 0;
const MIN_RENDER_INTERVAL = 16;
let _lastPerfRender = 0;
const MIN_PERF_RENDER_INTERVAL = 2000;

const logContainer = document.getElementById('logList');
const networkContainer = document.getElementById('networkList');
const playbackContainer = document.getElementById('playbackList');
const debugContainer = document.getElementById('debugList');
const crashContainer = document.getElementById('crashList');
const huyaContainer = document.getElementById('huyaList');
const serverStatus = document.getElementById('serverStatus');
const serverStatusText = document.getElementById('serverStatusText');
const deviceCount = document.getElementById('deviceCount');
const logCount = document.getElementById('logCount');
const deviceList = document.getElementById('deviceList');
const deviceFilter = document.getElementById('deviceFilter');
const typeFilter = document.getElementById('typeFilter');
const searchFilter = document.getElementById('searchFilter');
const toast = document.getElementById('toast');

const typeLabels = {
  info: '信息', warn: '警告', error: '错误', debug: '调试',
  crash: '崩溃', network: '网络', parse: '解析',
  playback: '播放', operation: '操作'
};

// ========== 表单输入反向插入修复 ==========
// 症状：清空内容后输入 42437 结果变成 73424（倒着输入）
// 根因：
//   1) inputmode="numeric" + 中文输入法/某些 Chromium/IME 会把新字符插到 value 开头
//   2) 第三方监听器把字符 prepend（ch + value）
// 防护：对 ADB 连接 / WiFi 配对 / 虎牙房间号 这些输入框
//   a) 强制 dir=ltr / 光标聚焦时放末尾
//   b) 记住上次 value，当检测到「只新增 1 个字符且该字符在 index=0」时，把那个字符搬到光标位置
//   c) 如果检测到「新增的多字符整体反转」（例如 42→24），也纠正
(function () {
  const protectedIds = [
    'deviceIp', 'devicePort',        // 手动连接
    'pairIp', 'pairPort', 'pairingCode', // WiFi 配对
    'huyaRoomId'                     // 虎牙房间号
  ];
  const lastValue = new Map();

  function forceLtr(el) {
    if (!el) return;
    el.setAttribute('dir', 'ltr');
    el.style.direction = 'ltr';
    el.style.textAlign = 'start';
    el.style.unicodeBidi = 'plaintext';
  }

  function moveCaretToEnd(el) {
    try {
      if (typeof el.selectionStart === 'number') {
        const end = (el.value || '').length;
        el.setSelectionRange(end, end);
      }
    } catch (_) { /* 某些 type 不支持，忽略 */ }
  }

  // 修复「输入被插到开头」的核心逻辑
  function fixReversedInsertion(el, prev, next) {
    if (prev == null) return next;
    if (prev === next) return next;

    // case 1: 单字符插入到开头，length 刚好 +1，next[0] 是新字符，next.slice(1) === prev
    if (next.length === prev.length + 1 && next.slice(1) === prev) {
      const ch = next.charAt(0);
      // 把这个字符放到之前光标应该在的位置（也就是 prev 的末尾 - 因为用户刚清空/输入时焦点在末尾）
      // 如果 prev 末尾本来就不是末尾，也放到 prev 末尾（清空后用户就是往末尾写）
      const corrected = prev + ch;
      return corrected;
    }

    // case 2: 一次粘贴/输入多字符后整体反转，例如 prev="" next="73424" 而用户想输的是 42437
    //         启发式：如果 next 非空且 prev 为空，那么用户刚清空，我们没法直接判断 —— 不自动 reverse，
    //         但如果用户接下来的按键继续表现为 case1，会被逐字符纠正。
    //         这里额外处理：如果是「prev 非空，next.length = prev.length + n，next 末尾 n 个字符按顺序拼起来等于 prev」
    //         说明连续 n 个字符都被反着插到了开头。把开头这 n 个字符拆出来 reverse 后拼到 prev 末尾
    const Lp = prev.length;
    const Ln = next.length;
    if (Ln > Lp && prev === next.slice(Ln - Lp)) {
      const prepended = next.slice(0, Ln - Lp);
      const corrected = prev + prepended.split('').reverse().join('');
      return corrected;
    }

    return next;
  }

  protectedIds.forEach(id => {
    const el = document.getElementById(id);
    if (!el) return;
    forceLtr(el);
    lastValue.set(id, el.value || '');

    el.addEventListener('focus', () => {
      forceLtr(el);
      // 聚焦后把光标放到末尾（除非用户自己点到了中间位置；focus 阶段无法判断，就统一放末尾）
      if (document.activeElement === el) {
        // 延迟一帧，避免 IME 重置位置
        setTimeout(() => moveCaretToEnd(el), 0);
      }
    });

    el.addEventListener('click', () => {
      forceLtr(el);
    });

    // beforeinput 时先记录 prev，避免被第三方监听器在 beforeinput 阶段就改写
    el.addEventListener('beforeinput', () => {
      lastValue.set(id, el.value || '');
    });

    el.addEventListener('input', () => {
      forceLtr(el);
      const prev = lastValue.get(id) ?? '';
      const cur = el.value || '';
      const corrected = fixReversedInsertion(el, prev, cur);
      if (corrected !== cur) {
        // 保持光标在末尾
        el.value = corrected;
        setTimeout(() => moveCaretToEnd(el), 0);
        lastValue.set(id, corrected);
      } else {
        lastValue.set(id, cur);
      }
    });

    // keydown 兜底：如果检测到扩展/监听器 preventDefault 了 keydown 但自己 prepend 字符
    // 这里在 keydown 之后 0ms 再扫一遍 value
    el.addEventListener('keydown', () => {
      const prev = el.value || '';
      lastValue.set(id, prev);
      setTimeout(() => {
        forceLtr(el);
        const cur = el.value || '';
        const corrected = fixReversedInsertion(el, prev, cur);
        if (corrected !== cur) {
          el.value = corrected;
          moveCaretToEnd(el);
          lastValue.set(id, corrected);
        }
      }, 0);
    });
  });
})();

function initWebSocket() {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  ws = new WebSocket(`${protocol}//${window.location.host}`);

  ws.onopen = () => {
    serverStatus.classList.remove('disconnected');
    serverStatus.classList.add('connected');
    serverStatusText.textContent = '已连接';
  };

  ws.onclose = () => {
    serverStatus.classList.remove('connected');
    serverStatus.classList.add('disconnected');
    serverStatusText.textContent = '连接断开';
    
    // 断开连接时自动清空日志
    if (autoClearOnDisconnect && logs.length > 0) {
      logs = [];
      renderLogs();
      updateStats();
      showToast('连接断开，已自动清空日志', 'info');
    }
    
    setTimeout(initWebSocket, 3000);
  };

  ws.onerror = () => {
    serverStatusText.textContent = '连接错误';
  };

  ws.onmessage = (event) => {
    const message = JSON.parse(event.data);
    handleServerMessage(message);
  };
}

function handleServerMessage(message) {
  switch (message.type) {
    case 'connected':
      break;
    case 'devices_list':
      updateDeviceList(message.devices);
      break;
    case 'device_connected':
      showToast(`设备已连接`, 'success');
      break;
    case 'device_disconnected':
      showToast('设备已断开', 'error');
      break;
    case 'device_init':
      updateDeviceInfo(message.deviceId, message.deviceInfo);
      break;
    case 'device_error':
      showToast(`设备错误: ${message.error}`, 'error');
      break;
    case 'log':
      if (!pauseUpdates) addLog(message.log);
      break;
    case 'logs_batch':
      if (!pauseUpdates && message.logs && message.logs.length > 0) {
        for (const log of message.logs) addLog(log);
      }
      break;
    case 'logs':
      logs = message.logs || [];
      if (logs.length > MAX_LOGS) logs.splice(0, logs.length - MAX_LOGS);
      pendingLogs = [];
      renderLogs();
      updateStats();
      break;
    case 'logs_cleared':
      logs = [];
      pendingLogs = [];
      renderLogs();
      showToast('日志已清空', 'success');
      break;
    case 'scan_results':
      discoveredDevices = message.devices || [];
      renderDiscoveredDevices();
      break;
  }
  if (currentView === 'stats') {
    updateStats();
  } else {
    logCount.textContent = logs.length;
  }
}

function addLog(log) {
  if (pauseUpdates) return;
  pendingLogs.push(log);
  if (!renderScheduled) {
    renderScheduled = true;
    requestAnimationFrame(flushPendingLogs);
  }
}

function addLogImmediate(log) {
  logs.push(log);
  if (logs.length > MAX_LOGS) logs.splice(0, logs.length - MAX_LOGS);
}

function flushPendingLogs() {
  const now = Date.now();
  const elapsed = now - lastRenderTime;

  if (elapsed < MIN_RENDER_INTERVAL && pendingLogs.length < 5) {
    renderScheduled = false;
    return;
  }

  const batch = pendingLogs.splice(0, pendingLogs.length);
  lastRenderTime = now;

  if (currentView === 'logs' || currentView === 'network' || currentView === 'playback' || currentView === 'debug' || currentView === 'crashes' || currentView === 'huya') {
    const newLogs = [];
    for (const log of batch) {
      addLogImmediate(log);
      newLogs.push(log);
    }

    if (logs.length > MAX_VISIBLE_LOGS) {
      renderLogs();
    } else {
      appendLogElements(newLogs);
    }
    if (currentView === 'stats') {
      updateStats();
    }
  } else {
    for (const log of batch) addLogImmediate(log);
    logCount.textContent = logs.length;

    // 性能视图下，有新日志时按节流频率刷新 renderPerformance（播放/网络计数会实时变化）
    if (currentView === 'performance') {
      const now2 = Date.now();
      if (now2 - _lastPerfRender >= MIN_PERF_RENDER_INTERVAL) {
        _lastPerfRender = now2;
        try { renderPerformance(); } catch (_) {}
      }
    }
  }

  renderScheduled = false;
  if (pendingLogs.length > 0) {
    requestAnimationFrame(flushPendingLogs);
  }
}

function appendLogElements(newLogs) {
  const container = getCurrentContainer();
  if (!container) return;

  // 如果启用了任何过滤（仅看应用日志/类型/设备/搜索），走完整 renderLogs 保证过滤一致
  const typeValue = typeFilter.value;
  const deviceValue = deviceFilter.value;
  const searchValue = searchFilter.value;
  if (filterSystemLogs || typeValue || deviceValue || searchValue) {
    renderLogs();
    return;
  }

  if (container.childElementCount >= MAX_VISIBLE_LOGS) {
    renderLogs();
    return;
  }

  const fragment = document.createDocumentFragment();
  const recentLogs = newLogs.slice(0, 50);
  for (const log of recentLogs) {
    const el = createLogElement(log);
    el.style.opacity = '0';
    el.style.transition = 'opacity 0.15s ease';
    fragment.appendChild(el);
  }

  if (container.firstChild && fragment.firstChild) {
    container.insertBefore(fragment, container.firstChild);
  } else {
    container.appendChild(fragment);
  }

  requestAnimationFrame(() => {
    const elements = container.querySelectorAll('.log-item[style*="opacity"]');
    elements.forEach(el => { el.style.opacity = ''; el.style.transition = ''; });
  });

  while (container.childElementCount > MAX_VISIBLE_LOGS) {
    container.removeChild(container.lastChild);
  }

  if (autoScroll && container.firstChild) {
    container.scrollTop = 0;
  }

  logCount.textContent = logs.length;
}

function getCurrentContainer() {
  switch (currentView) {
    case 'logs': return logContainer;
    case 'network': return networkContainer;
    case 'playback': return playbackContainer;
    case 'debug': return debugContainer;
    case 'crashes': return crashContainer;
    case 'huya': return huyaContainer;
    default: return null;
  }
}

function renderLogs() {
  const container = getCurrentContainer();
  if (!container) return;

  const targetTypeMap = {
    'logs': null,
    'network': 'network',
    'playback': 'playback',
    'debug': 'debug',
    'crashes': ['crash', 'error']
  };
  const targetType = targetTypeMap[currentView] || null;
  const typeValue = typeFilter.value;
  const combinedType = typeValue || targetType;
  const isArrayType = Array.isArray(combinedType);
  const searchValue = searchFilter.value;
  const hasSearch = !!searchValue;
  const searchLower = hasSearch ? searchValue.toLowerCase() : '';
  const deviceValue = deviceFilter.value;
  const hasDevice = !!deviceValue;

  const ringBuf = new Array(MAX_VISIBLE_LOGS);
  let bufLen = 0;
  let bufStart = 0;

  for (let i = 0; i < logs.length; i++) {
    const l = logs[i];
    if (filterSystemLogs && l.isSystemLog) continue;
    if (currentView === 'huya' && !l.isHuyaSdk) continue;
    const logType = l.logType || l.type || 'info';
    if (combinedType) {
      if (isArrayType) {
        if (!combinedType.includes(logType)) continue;
      } else {
        if (logType !== combinedType) continue;
      }
    }
    if (hasDevice && l.deviceId !== deviceValue) continue;
    if (hasSearch) {
      const tag = (l.tag || '').toLowerCase();
      const message = (l.message || '').toLowerCase();
      if (!tag.includes(searchLower) && !message.includes(searchLower)) continue;
    }
    ringBuf[bufStart] = l;
    bufStart = (bufStart + 1) % MAX_VISIBLE_LOGS;
    if (bufLen < MAX_VISIBLE_LOGS) bufLen++;
  }

  container.innerHTML = '';
  const fragment = document.createDocumentFragment();
  for (let i = 0; i < bufLen; i++) {
    const idx = (bufStart - 1 - i + MAX_VISIBLE_LOGS) % MAX_VISIBLE_LOGS;
    fragment.appendChild(createLogElement(ringBuf[idx]));
  }
  container.appendChild(fragment);
  if (autoScroll && container.firstChild) container.scrollTop = 0;

  logCount.textContent = logs.length;
}

function createLogElement(log) {
  const item = document.createElement('div');
  const logType = log.logType || log.type || 'info';
  item.className = `log-item ${logType}`;

  const timestamp = document.createElement('span');
  timestamp.className = 'log-timestamp';
  timestamp.textContent = formatTime(log.timestamp || log.serverTime);

  const device = document.createElement('span');
  device.className = 'log-device';
  const deviceName = getDeviceDisplayName(log.deviceId);
  device.textContent = deviceName;
  device.title = deviceName;

  const typeBadge = document.createElement('span');
  typeBadge.className = `log-type-badge type-${logType}`;
  typeBadge.textContent = typeLabels[logType] || logType;

  const tag = document.createElement('span');
  tag.className = 'log-tag';
  tag.textContent = log.tag ? `[${log.tag}]` : '';

  const message = document.createElement('span');
  message.className = 'log-message';
  if (logType === 'crash' || log.type === 'crash') {
    const pre = document.createElement('pre');
    pre.textContent = log.message || log.stackTrace || '';
    pre.style.background = 'var(--bg-secondary)';
    pre.style.padding = '8px';
    pre.style.borderRadius = '4px';
    pre.style.whiteSpace = 'pre-wrap';
    pre.style.fontSize = '11px';
    message.appendChild(pre);
  } else {
    message.textContent = log.message || '';
  }

  item.appendChild(timestamp);
  item.appendChild(device);
  item.appendChild(typeBadge);
  item.appendChild(tag);
  item.appendChild(message);
  return item;
}

function getFilteredLogs() {
  const typeValue = typeFilter.value;
  const deviceValue = deviceFilter.value;
  const searchValue = searchFilter.value.toLowerCase();
  const hasSearch = !!searchValue;
  const hasType = !!typeValue;
  const hasDevice = !!deviceValue;
  const hasSystemFilter = filterSystemLogs;

  if (!hasSearch && !hasType && !hasDevice && !hasSystemFilter) {
    return logs;
  }

  const result = [];
  for (let i = 0; i < logs.length; i++) {
    const l = logs[i];
    if (hasSystemFilter && l.isSystemLog) continue;
    if (hasType && (l.logType || l.type) !== typeValue) continue;
    if (hasDevice && l.deviceId !== deviceValue) continue;
    if (hasSearch) {
      const tag = (l.tag || '').toLowerCase();
      const message = (l.message || '').toLowerCase();
      if (!tag.includes(searchValue) && !message.includes(searchValue)) continue;
    }
    result.push(l);
  }
  return result;
}

function updateDeviceList(deviceListData) {
  devices.clear();
  if (deviceListData) {
    deviceListData.forEach(d => devices.set(d.id, { ...d, connected: d.connected !== false }));
  }
  renderDeviceList();
  updateDeviceFilter();
  deviceCount.textContent = devices.size;
}

function renderDeviceList() {
  deviceList.innerHTML = '';
  if (devices.size === 0) {
    deviceList.innerHTML = '<p class="empty-state">暂无已连接设备</p>';
    return;
  }

  for (const [id, device] of devices) {
    const item = document.createElement('div');
    item.className = 'device-item';
    const info = device.info || {};
    const displayName = info.deviceName || info.deviceModel || info.ip || id;
    const sourceType = info.connectionType || 'network';

    item.innerHTML = `
      <div class="device-info">
        <span class="device-name">${escapeHtml(displayName)}</span>
        <span class="source-badge ${sourceType}">${sourceType === 'ADB' ? 'ADB' : '网络'}</span>
        <span class="device-status ${device.connected ? 'online' : 'offline'}">
          ${device.connected ? '● 在线' : '○ 离线'}
        </span>
      </div>
      <div class="device-detail">
        ${info.ip ? `地址: ${escapeHtml(info.ip)}` : ''}
        ${info.appVersion ? `<br>版本: ${escapeHtml(info.appVersion)}` : ''}
        ${info.deviceModel ? `<br>型号: ${escapeHtml(info.deviceModel)}` : ''}
      </div>
      <div class="device-actions">
        <button onclick="disconnectDevice('${id}')" style="background: var(--danger); font-size: 11px; padding: 4px 8px; width: auto;">
          断开连接
        </button>
      </div>
    `;
    deviceList.appendChild(item);
  }
}

function updateDeviceInfo(deviceId, deviceInfo) {
  const device = devices.get(deviceId);
  if (device) {
    device.info = { ...device.info, ...deviceInfo };
    renderDeviceList();
    updateDeviceFilter();
  }
}

function updateDeviceFilter() {
  deviceFilter.innerHTML = '<option value="">全部设备</option>';
  for (const [id, device] of devices) {
    const info = device.info || {};
    const displayName = info.deviceName || info.deviceModel || info.ip || id;
    const option = document.createElement('option');
    option.value = id;
    option.textContent = displayName;
    deviceFilter.appendChild(option);
  }
}

function getDeviceDisplayName(deviceId) {
  if (!deviceId) return '未知设备';
  const device = devices.get(deviceId);
  if (device) {
    const info = device.info || {};
    return info.deviceName || info.deviceModel || info.ip || deviceId.substring(0, 8);
  }
  return deviceId.substring(0, 8);
}

function updateStats() {
  if (currentView !== 'stats') return;
  
  const total = logs.length;
  let crashes = 0, warnings = 0, errors = 0;
  const deviceLogCounts = {};
  const deviceTypeCounts = {};
  const typeCounts = {};
  
  for (let i = 0; i < logs.length; i++) {
    const log = logs[i];
    const type = log.logType || log.type || 'info';
    if (type === 'crash') crashes++;
    else if (type === 'warn') warnings++;
    else if (type === 'error') errors++;
    
    const id = log.deviceId || 'unknown';
    if (!deviceLogCounts[id]) { deviceLogCounts[id] = 0; deviceTypeCounts[id] = {}; }
    deviceLogCounts[id]++;
    deviceTypeCounts[id][type] = (deviceTypeCounts[id][type] || 0) + 1;
    typeCounts[type] = (typeCounts[type] || 0) + 1;
  }
  
  document.getElementById('statTotalLogs').textContent = total;
  document.getElementById('statCrashes').textContent = crashes;
  document.getElementById('statWarnings').textContent = warnings;
  document.getElementById('statErrors').textContent = errors;

  const deviceStatsEl = document.getElementById('deviceStats');
  deviceStatsEl.innerHTML = '';

  for (const [deviceId, count] of Object.entries(deviceLogCounts)) {
    const displayName = getDeviceDisplayName(deviceId);
    const tc = deviceTypeCounts[deviceId] || {};
    const card = document.createElement('div');
    card.className = 'device-stat-card';
    card.innerHTML = `
      <div class="device-name">${escapeHtml(displayName)}</div>
      <div class="device-stat-row"><span>日志总数</span><span>${count}</span></div>
      <div class="device-stat-row"><span>崩溃</span><span>${tc.crash || 0}</span></div>
      <div class="device-stat-row"><span>警告</span><span>${tc.warn || 0}</span></div>
      <div class="device-stat-row"><span>错误</span><span>${tc.error || 0}</span></div>
      <div class="device-stat-row"><span>网络</span><span>${tc.network || 0}</span></div>
      <div class="device-stat-row"><span>播放</span><span>${tc.playback || 0}</span></div>
    `;
    deviceStatsEl.appendChild(card);
  }

  const typeStatsEl = document.getElementById('typeStats');
  typeStatsEl.innerHTML = '';
  Object.keys(typeLabels).forEach(type => {
    const count = typeCounts[type] || 0;
    if (count > 0) {
      const item = document.createElement('div');
      item.className = 'type-stat-item';
      item.innerHTML = `<div class="type-stat-dot ${type}"></div><span class="type-stat-count">${count}</span><span class="type-stat-name">${typeLabels[type]}</span>`;
      typeStatsEl.appendChild(item);
    }
  });
}

function formatTime(timestamp) {
  if (!timestamp) return '';
  const date = new Date(timestamp);
  if (isNaN(date.getTime())) return String(timestamp);
  const pad = (n) => String(n).padStart(2, '0');
  return pad(date.getHours()) + ':' + pad(date.getMinutes()) + ':' + pad(date.getSeconds()) + '.' + String(date.getMilliseconds()).padStart(3, '0');
}

function formatDateTime(timestamp) {
  if (!timestamp) return '';
  const date = new Date(timestamp);
  if (isNaN(date.getTime())) return String(timestamp);
  const pad = (n) => String(n).padStart(2, '0');
  return date.getFullYear() + '-' + pad(date.getMonth() + 1) + '-' + pad(date.getDate())
    + ' ' + pad(date.getHours()) + ':' + pad(date.getMinutes()) + ':' + pad(date.getSeconds());
}

function escapeHtml(text) {
  const div = document.createElement('div');
  div.textContent = text;
  return div.innerHTML;
}

function showToast(message, type = 'info') {
  toast.textContent = message;
  toast.className = `toast show ${type}`;
  setTimeout(() => { toast.className = 'toast'; }, 3000);
}

window.disconnectDevice = async function(deviceId) {
  try {
    const device = devices.get(deviceId);
    if (!device) {
      showToast('设备不存在', 'error');
      return;
    }
    
    const isAdb = device.info && device.info.connectionType === 'ADB';
    const url = isAdb ? '/api/adb/disconnect' : '/api/device/disconnect';
    const body = isAdb 
      ? { deviceId: deviceId }  // ADB设备只需传递 deviceId
      : { deviceId };
    
    console.log(`[Disconnect] Sending request to ${url} with body:`, body);
    
    const response = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    });
    
    console.log(`[Disconnect] Response status: ${response.status}`);
    const result = await response.json();
    console.log(`[Disconnect] Response body:`, result);
    
    if (result.success) {
      devices.delete(deviceId);
      renderDeviceList();
      updateDeviceFilter();
      showToast('设备已断开', 'success');
    } else {
      showToast(result.error || '断开连接失败', 'error');
    }
  } catch (e) {
    console.error('[Disconnect] Error:', e);
    showToast('断开连接请求失败: ' + e.message, 'error');
  }
};

async function scanLanDevices() {
  const scanBtn = document.getElementById('scanBtn');
  const scanProgress = document.getElementById('scanProgress');
  const progressFill = document.getElementById('progressFill');
  const statusText = document.getElementById('scanStatusText');
  const discoveredList = document.getElementById('discoveredList');

  scanBtn.disabled = true;
  scanProgress.classList.remove('hidden');
  discoveredList.classList.add('hidden');
  
  let progress = 0;
  statusText.textContent = '正在扫描局域网...';
  progressFill.style.width = '0%';
  
  const progressInterval = setInterval(() => {
    progress += Math.random() * 15 + 5;
    if (progress > 90) progress = 90;
    progressFill.style.width = progress + '%';
  }, 300);

  try {
    const response = await fetch('/api/scan/devices?port=9527');
    const result = await response.json();
    
    clearInterval(progressInterval);
    progressFill.style.width = '100%';
    
    if (result.success && result.devices.length > 0) {
      discoveredDevices = result.devices;
      renderDiscoveredDevices();
      discoveredList.classList.remove('hidden');
      statusText.textContent = `扫描完成，发现 ${result.devices.length} 台设备`;
      showToast(`发现 ${result.devices.length} 台设备`, 'success');
    } else {
      statusText.textContent = '扫描完成，未发现设备';
      discoveredDevices = [];
      discoveredList.classList.add('hidden');
      showToast('未发现局域网设备', 'info');
    }
  } catch (e) {
    clearInterval(progressInterval);
    statusText.textContent = '扫描失败';
    showToast('扫描失败', 'error');
  } finally {
    setTimeout(() => {
      scanBtn.disabled = false;
      scanProgress.classList.add('hidden');
      progressFill.style.width = '0%';
    }, 2000);
  }
}

function renderDiscoveredDevices() {
  const container = document.getElementById('discoveredDevices');
  const countEl = document.getElementById('discoveredCount');
  container.innerHTML = '';
  countEl.textContent = discoveredDevices.length;

  discoveredDevices.forEach(device => {
    const item = document.createElement('div');
    item.className = 'discovered-device-item';
    item.innerHTML = `
      <div class="device-row">
        <div>
          <div class="device-name">${escapeHtml(device.deviceName || 'TV Live 设备')}</div>
          <div class="device-ip">${escapeHtml(device.ip)}:${device.port}</div>
        </div>
        <button class="connect-btn">连接</button>
      </div>
      ${device.deviceModel ? `<div style="font-size:11px;color:var(--text-muted);margin-top:4px;">${escapeHtml(device.deviceModel)} ${device.appVersion ? '· ' + escapeHtml(device.appVersion) : ''}</div>` : ''}
    `;
    
    item.querySelector('.connect-btn').onclick = async (e) => {
      e.stopPropagation();
      await connectLanDevice(device.ip, device.port);
    };
    item.onclick = async () => {
      await connectLanDevice(device.ip, device.port);
    };
    
    container.appendChild(item);
  });
}

async function connectLanDevice(ip, port) {
  showToast(`正在连接 ${ip}...`, 'info');
  try {
    const response = await fetch('/api/scan/connect', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ ip, port })
    });
    const result = await response.json();
    if (result.success) {
      showToast(`已连接到 ${ip}`, 'success');
    } else {
      showToast(`连接失败: ${result.error}`, 'error');
    }
  } catch (e) {
    showToast('连接失败', 'error');
  }
}

async function checkAdb() {
  try {
    const response = await fetch('/api/adb/check');
    const result = await response.json();
    const statusEl = document.getElementById('adbStatus');
    const statusText = document.getElementById('adbStatusText');
    const readyEl = document.getElementById('adbReady');
    
    statusEl.classList.remove('checking');
    
    if (result.available) {
      statusEl.classList.add('available');
      statusText.textContent = `✓ ADB 就绪 (${result.version.split('\n')[0]})`;
      adbAvailable = true;
      readyEl.classList.remove('hidden');
      
      // ADB就绪后自动扫描设备并连接模拟器
      setTimeout(async () => {
        try {
          const scanResponse = await fetch('/api/adb/devices');
          const scanResult = await scanResponse.json();
          
          if (scanResult.success && scanResult.devices.length > 0) {
            const emulators = scanResult.devices.filter(d => d.isEmulator && d.state === 'device');
            const availableDevices = scanResult.devices.filter(d => d.state === 'device');
            
            if (emulators.length > 0) {
              showToast(`检测到 ${emulators.length} 台模拟器，自动连接中...`, 'info');
              for (const emulator of emulators) {
                await connectAdbDevice(emulator.serial);
              }
            } else if (availableDevices.length === 1) {
              showToast(`检测到 1 台设备，自动连接中...`, 'info');
              await connectAdbDevice(availableDevices[0].serial);
            }
          }
        } catch (e) {}
      }, 800);
    } else {
      statusEl.classList.add('unavailable');
      statusText.textContent = `✗ ADB 未就绪 - ${result.hint || '请安装 Android SDK'}`;
      adbAvailable = false;
      readyEl.classList.add('hidden');
    }
  } catch (e) {
    const statusEl = document.getElementById('adbStatus');
    statusEl.classList.remove('checking');
    statusEl.classList.add('unavailable');
    document.getElementById('adbStatusText').textContent = '✗ 无法检测 ADB';
  }
}

async function scanAdbDevices() {
  const btn = document.getElementById('adbScanBtn');
  btn.disabled = true;
  btn.querySelector('span:last-child').textContent = '扫描中...';
  
  try {
    // 带 autoStart=true：服务端会把所有 state=device 且还没启动 logcat 的设备自动拉起抓日志
    // 这样解决了「工具箱下拉框里能看到设备，但点击扫描ADB设备后没日志输出」的问题
    const response = await fetch('/api/adb/devices?autoStart=true');
    const result = await response.json();
    const listEl = document.getElementById('adbDeviceList');
    const container = document.getElementById('adbDevices');
    const countEl = document.getElementById('adbDeviceCount');
    
    container.innerHTML = '';
    
    if (result.success && result.devices.length > 0) {
      countEl.textContent = result.devices.length;
      listEl.classList.remove('hidden');
      
      result.devices.forEach(device => {
        const item = document.createElement('div');
        item.className = 'adb-device-item';
        if (device.state !== 'device') item.classList.add('offline');
        
        const stateLabel = device.state === 'device' ? 'ADB已连接' : 
                          device.state === 'offline' ? '离线' :
                          device.state === 'authorizing' ? '正在授权…' : '未授权';
        
        const canConnect = device.state === 'device';
        item.innerHTML = `
          <div class="adb-serial">${escapeHtml(device.serial)}</div>
          <div class="adb-info">${escapeHtml(device.model || 'Unknown')}${device.isEmulator ? ' (模拟器)' : ''}</div>
          <span class="adb-state ${device.state}">${stateLabel}</span>
          ${canConnect ? '<div class="adb-hint">👆 点击连接抓取日志</div>' : ''}
        `;
        
        if (canConnect) {
          item.style.cursor = 'pointer';
          item.onclick = async () => {
            item.style.opacity = '0.6';
            item.style.pointerEvents = 'none';
            await connectAdbDevice(device.serial);
          };
        }
        
        container.appendChild(item);
      });
      
      // 如果只有一台设备且可用，仍调用 connectAdbDevice（内部会做去重；即使 logcat 已经被服务端 autoStart 拉起来了，
      // 也只是做一次端口转发/启动TVLive的重复尝试，无副作用）
      const availableDevices = result.devices.filter(d => d.state === 'device');
      if (availableDevices.length === 1) {
        showToast(`发现设备，正在自动连接并启动日志...`, 'info');
        await connectAdbDevice(availableDevices[0].serial);
      } else {
        showToast(`发现 ${result.devices.length} 台 ADB 设备，点击设备连接（已为可连接设备自动启动日志抓取）`, 'success');
      }
    } else {
      countEl.textContent = 0;
      listEl.classList.add('hidden');
      showToast('未发现 ADB 设备', 'info');
    }
  } catch (e) {
    showToast('扫描失败', 'error');
  } finally {
    btn.disabled = false;
    btn.querySelector('span:last-child').textContent = '扫描 ADB 设备';
  }
}

async function connectAdbDevice(serial) {
  showToast(`正在连接 ADB 设备 ${serial}...`, 'info');
  try {
    const response = await fetch('/api/adb/connect', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ serial, autoStart: true })
    });
    const result = await response.json();
    if (result.success) {
      showToast(`ADB 已连接: ${serial}`, 'success');
    } else {
      showToast(`连接失败: ${result.error}`, 'error');
    }
  } catch (e) {
    showToast('连接失败', 'error');
  }
}

document.querySelectorAll('.panel-tab').forEach(tab => {
  tab.addEventListener('click', () => {
    document.querySelectorAll('.panel-tab').forEach(t => t.classList.remove('active'));
    tab.classList.add('active');
    const tabName = tab.dataset.tab;
    document.getElementById('lanTab').classList.toggle('hidden', tabName !== 'lan');
    document.getElementById('adbTab').classList.toggle('hidden', tabName !== 'adb');
    if (tabName === 'adb') checkAdb();
  });
});

document.getElementById('scanBtn').addEventListener('click', scanLanDevices);
document.getElementById('adbScanBtn').addEventListener('click', scanAdbDevices);

// ADB 配对并自动连接
document.getElementById('adbPairForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  
  const ip = document.getElementById('pairIp').value.trim();
  const portStr = document.getElementById('pairPort').value.trim();
  const port = parseInt(portStr, 10);
  const pairingCode = document.getElementById('pairingCode').value.trim();
  const btn = document.getElementById('pairBtn');
  const resultEl = document.getElementById('pairResult');
  
  // 验证输入
  if (!ip || !port || !pairingCode) {
    resultEl.className = 'pair-result error';
    resultEl.textContent = '请填写所有必填字段';
    resultEl.classList.remove('hidden');
    return;
  }
  
  // 验证 IP 格式
  const ipPattern = /^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$/;
  if (!ipPattern.test(ip)) {
    resultEl.className = 'pair-result error';
    resultEl.textContent = 'IP 地址格式不正确';
    resultEl.classList.remove('hidden');
    return;
  }
  
  // 验证端口范围
  if (port < 1 || port > 65535) {
    resultEl.className = 'pair-result error';
    resultEl.textContent = '端口必须在 1-65535 之间';
    resultEl.classList.remove('hidden');
    return;
  }
  
  // 验证配对码格式
  if (!/^\d{6}$/.test(pairingCode)) {
    resultEl.className = 'pair-result error';
    resultEl.textContent = '配对码必须是 6 位数字';
    resultEl.classList.remove('hidden');
    return;
  }
  
  btn.disabled = true;
  btn.textContent = '配对并连接中...';
  resultEl.className = 'pair-result info';
  resultEl.textContent = `正在配对 ${ip}:${port} ...`;
  resultEl.classList.remove('hidden');
  
  try {
    const response = await fetch('/api/adb/pair', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ ip, port, pairingCode })
    });
    const result = await response.json();
    
    if (result.success) {
      if (result.autoConnectFailed) {
        resultEl.className = 'pair-result warning';
        resultEl.textContent = '⚠️ 配对成功，但自动连接失败。请点击"扫描ADB设备"手动连接';
        resultEl.classList.remove('hidden');
        showToast('配对成功，自动连接失败', 'warn');
      } else if (result.autoConnecting) {
        resultEl.className = 'pair-result info';
        resultEl.textContent = '✅ 配对成功，正在自动连接...';
        resultEl.classList.remove('hidden');
        showToast('配对成功，正在自动连接', 'info');
        
        // 3秒后刷新设备列表
        setTimeout(async () => {
          await scanAdbDevices();
        }, 3000);
      } else {
        resultEl.className = 'pair-result success';
        resultEl.textContent = '✅ 配对并连接成功！已开始抓取日志。';
        resultEl.classList.remove('hidden');
        showToast('配对连接成功', 'success');
        
        // 刷新设备列表
        setTimeout(async () => {
          await scanAdbDevices();
        }, 1000);
      }
    } else {
      resultEl.className = 'pair-result error';
      resultEl.textContent = '❌ 配对失败: ' + result.error;
      resultEl.classList.remove('hidden');
      showToast('配对失败', 'error');
    }
  } catch (e) {
    resultEl.className = 'pair-result error';
    resultEl.textContent = '❌ 配对请求失败: ' + e.message;
    resultEl.classList.remove('hidden');
    showToast('配对请求失败', 'error');
  } finally {
    btn.disabled = false;
    btn.textContent = '🔐 配对并自动连接';
  }
});

document.getElementById('connectForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  const ip = document.getElementById('deviceIp').value.trim();
  const port = parseInt(document.getElementById('devicePort').value) || 9527;
  const btn = document.getElementById('connectBtn');
  btn.disabled = true;
  btn.textContent = '连接中...';
  try {
    await connectLanDevice(ip, port);
    document.getElementById('deviceIp').value = '';
  } finally {
    btn.disabled = false;
    btn.textContent = '🔗 连接设备';
  }
});

document.getElementById('typeFilter').addEventListener('change', renderLogs);
document.getElementById('deviceFilter').addEventListener('change', renderLogs);
document.getElementById('searchFilter').addEventListener('input', renderLogs);

// 快速筛选按钮
document.querySelectorAll('.quick-filter-btn').forEach(btn => {
  btn.addEventListener('click', () => {
    const type = btn.dataset.type;
    document.querySelectorAll('.quick-filter-btn').forEach(b => b.classList.remove('active'));
    btn.classList.add('active');
    typeFilter.value = type;
    renderLogs();
  });
});

document.getElementById('clearLogsBtn').addEventListener('click', async () => {
  if (confirm('确定要清空所有日志吗？')) {
    await fetch('/api/logs/clear', { method: 'POST' });
    logs = [];
    renderLogs();
    showToast('日志已清空', 'success');
  }
});

// 立即清空按钮（无确认弹窗）
document.getElementById('quickClearBtn').addEventListener('click', async () => {
  await fetch('/api/logs/clear', { method: 'POST' });
  logs = [];
  renderLogs();
  updateStats();
  showToast('日志已清空', 'success');
});

document.getElementById('autoScroll').addEventListener('change', (e) => { autoScroll = e.target.checked; });
document.getElementById('pauseLogs').addEventListener('change', (e) => { pauseUpdates = e.target.checked; });
document.getElementById('autoClearOnDisconnect').addEventListener('change', (e) => { autoClearOnDisconnect = e.target.checked; });
document.getElementById('filterSystemLogs').addEventListener('change', (e) => {
  filterSystemLogs = e.target.checked;
  localStorage.setItem('filterSystemLogs', filterSystemLogs);
  renderLogs();
});

let tabSwitchTimer = null;
document.querySelectorAll('.tab').forEach(tab => {
  tab.addEventListener('click', () => {
    const newView = tab.dataset.view;
    if (newView === currentView) return;
    
    document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
    tab.classList.add('active');
    currentView = newView;
    document.getElementById('logsView').classList.toggle('hidden', currentView !== 'logs');
    document.getElementById('networkView').classList.toggle('hidden', currentView !== 'network');
    document.getElementById('playbackView').classList.toggle('hidden', currentView !== 'playback');
    document.getElementById('debugView').classList.toggle('hidden', currentView !== 'debug');
    document.getElementById('crashesView').classList.toggle('hidden', currentView !== 'crashes');
    document.getElementById('huyaView').classList.toggle('hidden', currentView !== 'huya');
    document.getElementById('statsView').classList.toggle('hidden', currentView !== 'stats');
    document.getElementById('timelineView').classList.toggle('hidden', currentView !== 'timeline');
    document.getElementById('performanceView').classList.toggle('hidden', currentView !== 'performance');
    document.getElementById('currentView').textContent = 
      currentView === 'logs' ? '显示全部日志' :
      currentView === 'network' ? '仅显示网络日志' :
      currentView === 'playback' ? '仅显示播放日志' :
      currentView === 'debug' ? '仅显示调试日志' :
      currentView === 'crashes' ? '仅显示崩溃日志' :
      currentView === 'huya' ? '🐯 虎牙SDK 日志' :
      currentView === 'timeline' ? '时间线视图' :
      currentView === 'performance' ? '性能分析视图' :
      '显示统计信息';
    
    if (tabSwitchTimer) cancelAnimationFrame(tabSwitchTimer);
    
    tabSwitchTimer = requestAnimationFrame(() => {
      if (currentView === 'timeline') {
        renderTimeline();
      } else if (currentView === 'performance') {
        renderPerformance();
        setTimeout(() => {
          const canvas = document.getElementById('perfCanvas');
          if (canvas && logs.length >= 2) {
            drawPerformanceChart();
          }
        }, 100);
      } else {
        if (perfUpdateInterval) {
          clearInterval(perfUpdateInterval);
          perfUpdateInterval = null;
        }
        renderLogs();
      }
    });
  });
});

function updateUptime() {
  const elapsed = Math.floor((Date.now() - startTime) / 1000);
  const hours = Math.floor(elapsed / 3600);
  const minutes = Math.floor((elapsed % 3600) / 60);
  const seconds = elapsed % 60;
  let timeStr = '';
  if (hours > 0) timeStr += `${hours}小时 `;
  if (minutes > 0) timeStr += `${minutes}分 `;
  timeStr += `${seconds}秒`;
  document.getElementById('uptime').textContent = `运行时长: ${timeStr}`;
}

setInterval(updateUptime, 1000);
initWebSocket();
checkAdb();

// 主题切换功能
const themeToggleBtn = document.getElementById('themeToggleBtn');
const themeIcon = themeToggleBtn.querySelector('span') || null;

function applyTheme(theme) {
  if (theme === 'light') {
    document.body.classList.add('light-theme');
    themeToggleBtn.textContent = '🌙 切换主题';
  } else {
    document.body.classList.remove('light-theme');
    themeToggleBtn.textContent = '☀️ 切换主题';
  }
}

// 初始化主题
const savedTheme = localStorage.getItem('logMonitorTheme') || 'dark';
applyTheme(savedTheme);

// 主题切换事件
themeToggleBtn.addEventListener('click', () => {
  const isLight = document.body.classList.contains('light-theme');
  const newTheme = isLight ? 'dark' : 'light';
  applyTheme(newTheme);
  localStorage.setItem('logMonitorTheme', newTheme);
});

// 页面关闭时自动清空日志
window.addEventListener('beforeunload', async () => {
  if (logs.length > 0) {
    try {
      await fetch('/api/logs/clear', { method: 'POST', keepalive: true });
    } catch (e) {}
  }
});

// 页面隐藏时清空日志（切换到其他标签页或最小化时）
document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'hidden' && autoClearOnDisconnect && logs.length > 0) {
    logs = [];
    renderLogs();
    updateStats();
  }
});

// 初始化时恢复设置
const savedAutoClear = localStorage.getItem('autoClearOnDisconnect');
if (savedAutoClear !== null) {
  autoClearOnDisconnect = savedAutoClear === 'true';
  document.getElementById('autoClearOnDisconnect').checked = autoClearOnDisconnect;
}

// 恢复仅看应用日志设置
const savedFilterSystemLogs = localStorage.getItem('filterSystemLogs');
if (savedFilterSystemLogs !== null) {
  filterSystemLogs = savedFilterSystemLogs === 'true';
  document.getElementById('filterSystemLogs').checked = filterSystemLogs;
}

// 保存自动清空设置
document.getElementById('autoClearOnDisconnect').addEventListener('change', (e) => {
  autoClearOnDisconnect = e.target.checked;
  localStorage.setItem('autoClearOnDisconnect', autoClearOnDisconnect);
});

// ADB扫描按钮事件
document.getElementById('adbScanBtn').addEventListener('click', scanAdbDevices);

// 快速连接模拟器按钮
document.getElementById('emulatorConnectBtn').addEventListener('click', async () => {
  const btn = document.getElementById('emulatorConnectBtn');
  btn.disabled = true;
  const originalText = btn.querySelector('span:last-child').textContent;
  btn.querySelector('span:last-child').textContent = '连接中...';
  
  try {
    const response = await fetch('/api/adb/devices');
    const result = await response.json();
    
    if (result.success && result.devices.length > 0) {
      const emulators = result.devices.filter(d => d.isEmulator && d.state === 'device');
      
      if (emulators.length > 0) {
        showToast(`发现 ${emulators.length} 台模拟器，正在连接...`, 'info');
        for (const emulator of emulators) {
          await connectAdbDevice(emulator.serial);
        }
      } else {
        const availableDevices = result.devices.filter(d => d.state === 'device');
        if (availableDevices.length > 0) {
          showToast(`未发现模拟器，连接所有可用设备...`, 'info');
          for (const device of availableDevices) {
            await connectAdbDevice(device.serial);
          }
        } else {
          showToast('未发现任何可用设备，请启动模拟器', 'error');
        }
      }
    } else {
      showToast('未发现 ADB 设备，请启动模拟器', 'error');
    }
  } catch (e) {
    showToast('扫描失败', 'error');
  } finally {
    btn.disabled = false;
    btn.querySelector('span:last-child').textContent = originalText;
  }
});

// ========== ADB 工具箱 ==========

function getToolboxSerial() {
  const sel = document.getElementById('toolboxDeviceSel');
  return sel ? sel.value : '';
}

// 选择性能监控用的设备 serial：优先性能卡自己的 select，否则用工具箱的
function getPerformanceSerial() {
  const perfSel = document.getElementById('deviceSelect');
  const toolSel = document.getElementById('toolboxDeviceSel');
  const perfVal = perfSel && perfSel.value ? perfSel.value : '';
  const toolVal = toolSel && toolSel.value ? toolSel.value : '';
  return perfVal || toolVal || '';
}

// 渲染 options 到某个 select 元素（统一格式：serial + 模拟器标签 + 型号）
function _renderDeviceOptions(sel, devices, { prevValue, placeholder, autoSelectSingle, emitChange }) {
  if (!sel) return null;
  sel.innerHTML = `<option value="">${placeholder || '请选择设备'}</option>`;
  devices.forEach(d => {
    const opt = document.createElement('option');
    opt.value = d.serial;
    const label = d.isEmulator ? ' (模拟器)' : '';
    const model = d.model && d.model !== 'Unknown' ? ` [${d.model}]` : '';
    opt.textContent = `${d.serial}${label}${model}`;
    sel.appendChild(opt);
  });
  let picked = '';
  if (prevValue && devices.find(d => d.serial === prevValue)) {
    picked = prevValue;
  } else if (autoSelectSingle && devices.length === 1) {
    picked = devices[0].serial;
  } else if (devices.length > 0 && !prevValue) {
    // 默认挑第一个 device 状态的，避免性能卡永远显示"未连接"
    picked = devices[0].serial;
  }
  if (picked) {
    sel.value = picked;
    if (emitChange) sel.dispatchEvent(new Event('change'));
  }
  return picked;
}

// 把工具箱 select 与性能卡 select 互相同步（改变其中一个，另一个跟随；避免两处手动维护）
let _syncingSelects = false;
function _bindDeviceSelectsSync() {
  const perfSel = document.getElementById('deviceSelect');
  const toolSel = document.getElementById('toolboxDeviceSel');
  [perfSel, toolSel].forEach(sel => {
    if (!sel) return;
    if (sel.dataset._perfBound === '1') return;
    sel.dataset._perfBound = '1';
    sel.addEventListener('change', () => {
      if (_syncingSelects) return;
      _syncingSelects = true;
      try {
        const other = sel === perfSel ? toolSel : perfSel;
        const v = sel.value;
        if (other && v && other.value !== v) {
          // 若对方 options 里包含这个值才同步，否则不改（可能工具箱拉到新设备但性能卡尚未刷新）
          const found = Array.from(other.options).some(o => o.value === v);
          if (found) {
            other.value = v;
            other.dispatchEvent(new Event('change'));
          }
        }
        // 用户切换了监控设备 → 立即拉一次性能数据，不再等 5s 轮询
        fetchDevicePerformance().catch(() => {});
      } finally {
        _syncingSelects = false;
      }
    });
  });
}

// 统一刷新两个设备下拉（工具箱 + 实时性能卡），并自动选一个可用设备
async function updateAllDeviceSelects(opts = {}) {
  const toolSel = document.getElementById('toolboxDeviceSel');
  const perfSel = document.getElementById('deviceSelect');
  _bindDeviceSelectsSync();
  const prevTool = toolSel ? toolSel.value : '';
  const prevPerf = perfSel ? perfSel.value : '';
  const fallbackPick = prevPerf || prevTool;
  let devices = [];
  try {
    const r = await fetch('/api/adb/devices');
    const result = await r.json();
    if (!result.success) return [];
    devices = (result.devices || []).filter(d => d.state === 'device');
  } catch (_) { return []; }

  _syncingSelects = true;
  try {
    const pickedForTool = _renderDeviceOptions(toolSel, devices, {
      prevValue: prevTool,
      placeholder: '请选择设备',
      autoSelectSingle: true,
      emitChange: false
    });
    const pickedForPerf = _renderDeviceOptions(perfSel, devices, {
      prevValue: fallbackPick || pickedForTool,
      placeholder: '请选择要读取数据的设备',
      autoSelectSingle: true,
      emitChange: false
    });
    // 兜底：性能卡空但工具箱选上了 → 跟着工具箱
    if (perfSel && !perfSel.value && pickedForTool) {
      perfSel.value = pickedForTool;
    }
    // 兜底：工具箱空但性能卡选上了 → 跟着性能卡
    if (toolSel && !toolSel.value && pickedForPerf) {
      toolSel.value = pickedForPerf;
    }
  } finally {
    _syncingSelects = false;
  }
  return devices;
}

// 向后兼容：原 setInterval(updateToolboxDevices, 3000) 与 btn listener 都调用这个名字
function updateToolboxDevices() {
  return updateAllDeviceSelects();
}

function showToolboxResult(html, type = 'info') {
  const result = document.getElementById('toolboxResult');
  if (!result) return;
  result.innerHTML = `
    <div class="result-header">
      <span class="result-title">📋 操作结果</span>
      <button class="result-close" onclick="document.getElementById('toolboxResult').classList.add('hidden')">✕</button>
    </div>
    <div class="result-content result-${type}">${html}</div>
  `;
  result.classList.remove('hidden');
}

function showToolboxPrompt(title, fields, onSubmit) {
  const result = document.getElementById('toolboxResult');
  if (!result) return;
  let html = `<div class="result-header"><span class="result-title">${title}</span>
    <button class="result-close" onclick="document.getElementById('toolboxResult').classList.add('hidden')">✕</button></div>`;
  fields.forEach(f => {
    if (f.type === 'select') {
      html += `<label style="font-size:12px;color:var(--text-secondary);margin-top:8px;display:block">${f.label}</label>`;
      html += `<select id="prompt_${f.name}">`;
      f.options.forEach(o => { html += `<option value="${o.value}">${o.label}</option>`; });
      html += `</select>`;
    } else if (f.type === 'textarea') {
      html += `<label style="font-size:12px;color:var(--text-secondary);margin-top:8px;display:block">${f.label}</label>`;
      html += `<textarea id="prompt_${f.name}" rows="3" placeholder="${f.placeholder || ''}"></textarea>`;
    } else {
      html += `<label style="font-size:12px;color:var(--text-secondary);margin-top:8px;display:block">${f.label}</label>`;
      html += `<input type="text" id="prompt_${f.name}" placeholder="${f.placeholder || ''}" value="${f.value || ''}">`;
    }
  });
  html += `<div class="btn-row"><button id="promptSubmit" style="background:var(--primary);color:white">执行</button></div>`;
  result.innerHTML = html;
  result.classList.remove('hidden');
  document.getElementById('promptSubmit').onclick = () => {
    const values = {};
    fields.forEach(f => { values[f.name] = document.getElementById(`prompt_${f.name}`).value; });
    result.classList.add('hidden');
    onSubmit(values);
  };
}

async function toolboxPost(endpoint, body, resultType = 'text') {
  const serial = getToolboxSerial();
  if (!serial) { showToast('请先选择设备', 'error'); return; }
  const payload = { serial, ...body };
  try {
    const res = await fetch(endpoint, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    });
    const data = await res.json();
    if (resultType === 'image' && data.success && data.image) {
      showToolboxResult(`<div class="result-image"><img src="${data.image}" alt="截图"></div>`, 'success');
    } else if (resultType === 'perf' && data.success && data.perf) {
      let html = '<div class="perf-grid">';
      Object.entries(data.perf).forEach(([k, v]) => {
        html += `<div class="perf-item"><div class="perf-label">${k}</div><div class="perf-value" style="font-size:11px;color:var(--text-primary);word-break:break-all">${v.replace(/\n/g, '<br>')}</div></div>`;
      });
      html += '</div>';
      showToolboxResult(html, 'success');
    } else if (data.success) {
      showToolboxResult(`<pre style="white-space:pre-wrap">${data.output || data.message || '操作成功'}</pre>`, 'success');
    } else {
      showToolboxResult(`<span class="result-error">❌ ${data.error || '操作失败'}</span>`, 'error');
    }
  } catch (e) {
    showToolboxResult(`<span class="result-error">❌ 请求失败: ${e.message}</span>`, 'error');
  }
}

async function toolboxGet(endpoint) {
  const serial = getToolboxSerial();
  if (!serial) { showToast('请先选择设备', 'error'); return null; }
  try {
    const res = await fetch(`${endpoint}?serial=${encodeURIComponent(serial)}`);
    return await res.json();
  } catch (e) {
    showToolboxResult(`<span class="result-error">❌ 请求失败: ${e.message}</span>`, 'error');
    return null;
  }
}

// 屏幕截图
document.getElementById('btnScreenshot').addEventListener('click', () => {
  showToast('正在截取屏幕...', 'info');
  toolboxPost('/api/adb/screenshot', {}, 'image');
});

// 屏幕录制
document.getElementById('btnScreenrecord').addEventListener('click', () => {
  showToolboxPrompt('🎥 屏幕录制', [
    { name: 'duration', label: '录制时长（秒）', value: '10', placeholder: '5-60秒' }
  ], async (v) => {
    showToast(`正在录制 ${v.duration} 秒...`, 'info');
    const serial = getToolboxSerial();
    if (!serial) return;
    try {
      const res = await fetch('/api/adb/screenrecord', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ serial, duration: v.duration })
      });
      if (res.headers.get('content-type')?.includes('application/json')) {
        const data = await res.json();
        showToolboxResult(data.success ? `<pre>${data.output}</pre>` : `<span class="result-error">${data.error}</span>`, data.success ? 'success' : 'error');
      } else {
        const url = URL.createObjectURL(await res.blob());
        showToolboxResult(`<a href="${url}" download="screenrecord.mp4" class="result-link">⬇️ 下载录屏文件</a>`, 'success');
      }
    } catch (e) {
      showToolboxResult(`<span class="result-error">${e.message}</span>`, 'error');
    }
  });
});

// 安装 APK
document.getElementById('btnInstallApk').addEventListener('click', () => {
  const input = document.createElement('input');
  input.type = 'file';
  input.accept = '.apk';
  input.onchange = async (e) => {
    const file = e.target.files[0];
    if (!file) return;
    const serial = getToolboxSerial();
    if (!serial) { showToast('请先选择设备', 'error'); return; }

    // 前端以 application/octet-stream 直传二进制文件，避免 base64 膨胀 33% 并踩 JSON size 上限
    const url = new URL('/api/adb/install', window.location.origin);
    url.searchParams.set('serial', serial);
    url.searchParams.set('fileName', file.name);
    showToast(`正在上传并安装 APK (${(file.size / 1024 / 1024).toFixed(2)}MB)...`, 'info');
    try {
      const res = await fetch(url.toString(), {
        method: 'POST',
        headers: {
          'Content-Type': 'application/octet-stream',
          'X-File-Name': encodeURIComponent(file.name)
        },
        body: file
      });
      let data;
      try {
        data = await res.json();
      } catch (parseE) {
        const txt = await res.text();
        throw new Error(`服务器返回非 JSON (HTTP ${res.status}): ${txt.slice(0, 120)}`);
      }
      showToolboxResult(
        data.success
          ? `<pre>${data.output || '安装成功'}</pre>`
          : `<span class="result-error">${data.error || '安装失败'}</span>`,
        data.success ? 'success' : 'error'
      );
    } catch (e) {
      showToolboxResult(`<span class="result-error">${e.message}</span>`, 'error');
    }
  };
  input.click();
});

function fileToBase64(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result);
    reader.onerror = reject;
    reader.readAsDataURL(file);
  });
}

// 清除应用数据
document.getElementById('btnClearData').addEventListener('click', () => {
  showToolboxPrompt('🧹 清除应用数据', [
    { name: 'packageName', label: '包名', value: 'com.tv.live', placeholder: '要清除数据的包名' }
  ], (v) => {
    if (confirm(`确定要清除 ${v.packageName} 的数据吗？`)) {
      toolboxPost('/api/adb/clear-data', { packageName: v.packageName });
    }
  });
});

// 重启设备
document.getElementById('btnReboot').addEventListener('click', () => {
  showToolboxPrompt('🔄 重启设备', [
    { name: 'mode', label: '重启模式', type: 'select', options: [
      { value: 'normal', label: '正常重启' },
      { value: 'recovery', label: 'Recovery 模式' },
      { value: 'bootloader', label: 'Bootloader 模式' }
    ]}
  ], (v) => {
    if (confirm(`确定要重启设备吗？`)) {
      toolboxPost('/api/adb/reboot', { mode: v.mode });
    }
  });
});

// 系统信息
document.getElementById('btnSystemInfo').addEventListener('click', async () => {
  showToast('正在获取系统信息...', 'info');
  const data = await toolboxPost('/api/adb/system-info', {}, 'info');
  // Handled by toolboxPost
});

// 推送文件
document.getElementById('btnPushFile').addEventListener('click', () => {
  const input = document.createElement('input');
  input.type = 'file';
  input.onchange = (e) => {
    const file = e.target.files[0];
    if (!file) return;
    showToolboxPrompt('📤 推送到设备', [
      { name: 'remotePath', label: '设备端路径', value: '/sdcard/' + file.name, placeholder: '/sdcard/...' }
    ], async (v) => {
      const serial = getToolboxSerial();
      if (!serial) return;
      const remotePath = (v.remotePath || '').trim() || ('/sdcard/' + file.name);
      showToast(`正在推送 (${(file.size / 1024 / 1024).toFixed(2)}MB) → ${remotePath}`, 'info');
      try {
        const url = new URL('/api/adb/push', window.location.origin);
        url.searchParams.set('serial', serial);
        url.searchParams.set('fileName', file.name);
        url.searchParams.set('remotePath', remotePath);
        const res = await fetch(url.toString(), {
          method: 'POST',
          headers: {
            'Content-Type': 'application/octet-stream',
            'X-File-Name': encodeURIComponent(file.name),
            'X-Remote-Path': encodeURIComponent(remotePath)
          },
          body: file
        });
        let data;
        try {
          data = await res.json();
        } catch (parseE) {
          const txt = await res.text();
          throw new Error(`服务器返回非 JSON (HTTP ${res.status}): ${txt.slice(0, 120)}`);
        }
        showToolboxResult(
          data.success ? `<pre>${data.output || '推送成功'}</pre>` : `<span class="result-error">${data.error || '推送失败'}</span>`,
          data.success ? 'success' : 'error'
        );
      } catch (e) {
        showToolboxResult(`<span class="result-error">${e.message}</span>`, 'error');
      }
    });
  };
  input.click();
});

// 拉取文件
document.getElementById('btnPullFile').addEventListener('click', () => {
  showToolboxPrompt('📥 从设备拉取', [
    { name: 'remotePath', label: '设备端路径', placeholder: '/sdcard/...' }
  ], async (v) => {
    showToast('正在拉取...', 'info');
    const serial = getToolboxSerial();
    if (!serial) return;
    try {
      const res = await fetch('/api/adb/pull', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ serial, remotePath: v.remotePath })
      });
      if (res.headers.get('content-type')?.includes('application/json')) {
        const data = await res.json();
        showToolboxResult(`<span class="result-error">${data.error}</span>`, 'error');
      } else {
        const url = URL.createObjectURL(await res.blob());
        showToolboxResult(`<a href="${url}" download class="result-link">⬇️ 下载文件</a>`, 'success');
      }
    } catch (e) {
      showToolboxResult(`<span class="result-error">${e.message}</span>`, 'error');
    }
  });
});

// Shell 命令
document.getElementById('btnShellCmd').addEventListener('click', () => {
  showToolboxPrompt('⚙️ 执行 Shell 命令', [
    { name: 'command', label: '命令', type: 'textarea', placeholder: '例如: ls /sdcard' }
  ], (v) => {
    toolboxPost('/api/adb/shell', { command: v.command });
  });
});

// 性能监控
document.getElementById('btnPerfMon').addEventListener('click', () => {
  showToast('正在获取性能数据...', 'info');
  toolboxPost('/api/adb/perf', {}, 'perf');
});

// 应用列表
document.getElementById('btnListPackages').addEventListener('click', async () => {
  showToast('正在获取应用列表...', 'info');
  const data = await toolboxGet('/api/adb/packages-all');
  if (data && data.success) {
    let html = `<div style="max-height:300px;overflow-y:auto">`;
    data.packages.forEach(pkg => {
      html += `<div style="display:flex;justify-content:space-between;align-items:center;padding:4px 8px;border-bottom:1px solid var(--border-color)">
        <code>${pkg}</code>
        <button class="btn-uninstall" data-pkg="${pkg}" style="color:var(--crash);background:none;border:none;cursor:pointer;font-size:11px">卸载</button>
      </div>`;
    });
    html += `</div>`;
    html += `<script>document.querySelectorAll('.btn-uninstall').forEach(b=>b.onclick=()=>{if(confirm('卸载 '+b.dataset.pkg+'?'))toolboxPost('/api/adb/uninstall',{packageName:b.dataset.pkg})})<\/script>`;
    showToolboxResult(html, 'success');
  }
});

// WiFi 开关
document.getElementById('btnToggleWifi').addEventListener('click', () => {
  showToolboxPrompt('📡 WiFi 开关', [
    { name: 'action', label: '操作', type: 'select', options: [
      { value: 'enable', label: '开启 WiFi' },
      { value: 'disable', label: '关闭 WiFi' }
    ]}
  ], (v) => {
    toolboxPost('/api/adb/wifi', { enable: v.action === 'enable' });
  });
});

// 初始化工具箱设备列表
document.getElementById('btnRefreshDevices').addEventListener('click', () => {
  updateToolboxDevices();
  showToast('设备列表已刷新', 'success');
});
setInterval(updateToolboxDevices, 3000);
updateToolboxDevices();

// ========== TVLive 专属工具 ==========

function getTvliveSerial() { return getToolboxSerial(); }

function showTvliveResult(html, type = 'info') {
  const result = document.getElementById('tvliveResult');
  if (!result) return;
  result.innerHTML = `
    <div class="result-header">
      <span class="result-title">📺 TVLive 操作结果</span>
      <button class="result-close" onclick="document.getElementById('tvliveResult').classList.add('hidden')">✕</button>
    </div>
    <div class="result-content result-${type}">${html}</div>
  `;
  result.classList.remove('hidden');
}

async function tvlivePost(endpoint, body) {
  const serial = getTvliveSerial();
  if (!serial) { showToast('请先选择设备', 'error'); return; }
  try {
    const res = await fetch(endpoint, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ serial, ...body })
    });
    const data = await res.json();
    if (data.success) {
      showTvliveResult(`<pre>${data.output || data.message || '操作成功'}</pre>`, 'success');
    } else {
      showTvliveResult(`<span class="result-error">❌ ${data.error || '操作失败'}</span>`, 'error');
    }
    return data;
  } catch (e) {
    showTvliveResult(`<span class="result-error">❌ ${e.message}</span>`, 'error');
  }
}

// TVLive 状态检测
async function checkTvliveStatus() {
  const serial = getTvliveSerial();
  if (!serial) return;
  try {
    const res = await fetch(`/api/tvlive/status?serial=${encodeURIComponent(serial)}`);
    const data = await res.json();
    const dot = document.getElementById('tvliveStatusDot');
    const text = document.getElementById('tvliveStatusText');
    const pidEl = document.getElementById('tvlivePid');
    const card = document.querySelector('.tvlive-status-info');
    if (data.running) {
      dot.textContent = '●';
      dot.style.color = '#48bb78';
      text.textContent = '运行中';
      card.classList.add('running');
      card.classList.remove('stopped');
      pidEl.textContent = `PID: ${data.pid || ''}`;
    } else {
      dot.textContent = '●';
      dot.style.color = '#f56565';
      text.textContent = '已停止';
      card.classList.add('stopped');
      card.classList.remove('running');
      pidEl.textContent = '';
    }
  } catch (e) {}
}

setInterval(checkTvliveStatus, 5000);
checkTvliveStatus();

// 启动 TVLive
document.getElementById('btnTvliveStart').addEventListener('click', () => {
  showToast('正在启动 TVLive...', 'info');
  tvlivePost('/api/tvlive/start');
  setTimeout(checkTvliveStatus, 2000);
});

// 停止 TVLive
document.getElementById('btnTvliveStop').addEventListener('click', () => {
  if (confirm('确定要停止 TVLive 吗？')) {
    showToast('正在停止 TVLive...', 'info');
    tvlivePost('/api/tvlive/stop');
    setTimeout(checkTvliveStatus, 1000);
  }
});

// 重启 TVLive
document.getElementById('btnTvliveRestart').addEventListener('click', () => {
  if (confirm('确定要重启 TVLive 吗？')) {
    showToast('正在重启 TVLive...', 'info');
    tvlivePost('/api/tvlive/restart');
    setTimeout(checkTvliveStatus, 3000);
  }
});

// TVLive 运行状态
document.getElementById('btnTvliveStatus').addEventListener('click', async () => {
  const serial = getTvliveSerial();
  if (!serial) return;
  try {
    const res = await fetch(`/api/tvlive/status?serial=${encodeURIComponent(serial)}`);
    const data = await res.json();
    let html = `<div style="display:grid;grid-template-columns:1fr 1fr;gap:8px">`;
    html += `<div class="perf-item"><div class="perf-label">运行状态</div><div class="perf-value" style="color:${data.running ? '#48bb78' : '#f56565'}">${data.running ? '● 运行中' : '○ 已停止'}</div></div>`;
    html += `<div class="perf-item"><div class="perf-label">进程 PID</div><div class="perf-value">${data.pid || '-'}</div></div>`;
    if (data.memory) html += `<div class="perf-item"><div class="perf-label">内存使用</div><div class="perf-value" style="font-size:11px">${data.memory.replace(/\n/g, '<br>')}</div></div>`;
    if (data.activity) html += `<div class="perf-item" style="grid-column:span 2"><div class="perf-label">当前 Activity</div><div class="perf-value" style="font-size:11px;word-break:break-all">${data.activity}</div></div>`;
    html += `</div>`;
    showTvliveResult(html, 'success');
  } catch (e) {
    showTvliveResult(`<span class="result-error">${e.message}</span>`, 'error');
  }
});

// 遥控器按键
document.querySelectorAll('.remote-btn').forEach(btn => {
  btn.addEventListener('click', () => {
    const key = btn.dataset.key;
    showToast(`发送按键: ${btn.textContent.trim()}`, 'info');
    tvlivePost('/api/tvlive/key', { keyCode: key });
  });
});

// 端口转发
document.getElementById('btnTvliveForward').addEventListener('click', () => {
  showTvliveResult(`
    <label style="font-size:12px;color:var(--text-secondary);display:block">本地端口</label>
    <input type="text" id="fwdLocal" value="9527">
    <label style="font-size:12px;color:var(--text-secondary);display:block;margin-top:6px">远程端口</label>
    <input type="text" id="fwdRemote" value="9527">
    <div class="btn-row">
      <button id="fwdSubmit" style="background:var(--primary);color:white">建立转发</button>
    </div>
  `, 'info');
  document.getElementById('fwdSubmit').onclick = async () => {
    const localPort = document.getElementById('fwdLocal').value;
    const remotePort = document.getElementById('fwdRemote').value;
    showToast('正在建立端口转发...', 'info');
    const serial = getTvliveSerial();
    if (!serial) return;
    try {
      const res = await fetch('/api/tvlive/forward', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ serial, localPort, remotePort })
      });
      const data = await res.json();
      if (data.success) {
        showTvliveResult(`<pre>${data.output}</pre><p style="margin-top:8px">现在可以通过 http://localhost:${localPort} 访问 TVLive WebServer</p>`, 'success');
      } else {
        showTvliveResult(`<span class="result-error">${data.error}</span>`, 'error');
      }
    } catch (e) {
      showTvliveResult(`<span class="result-error">${e.message}</span>`, 'error');
    }
  };
});

// 移除端口转发
document.getElementById('btnTvliveRemoveForward').addEventListener('click', () => {
  const serial = getTvliveSerial();
  if (!serial) return;
  fetch('/api/tvlive/forward-remove', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ serial, localPort: 9527 })
  }).then(r => r.json()).then(data => {
    showTvliveResult(data.success ? `<pre>${data.output}</pre>` : `<span class="result-error">${data.error}</span>`, data.success ? 'success' : 'error');
  });
});

// 设备 IP
document.getElementById('btnTvliveDeviceIp').addEventListener('click', () => {
  showToast('正在获取设备 IP...', 'info');
  tvlivePost('/api/tvlive/device-ip');
});

// 导出日志
document.getElementById('btnTvliveExportLogs').addEventListener('click', () => {
  const serial = getTvliveSerial();
  if (!serial) return;
  showToast('正在导出日志...', 'info');
  fetch('/api/tvlive/export-logs', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ serial, tagFilter: 'TVLive' })
  }).then(res => {
    if (res.headers.get('content-type')?.includes('application/json')) {
      return res.json().then(data => {
        showTvliveResult(`<span class="result-error">${data.error}</span>`, 'error');
      });
    }
    const url = URL.createObjectURL(new Blob([res.body], { type: 'text/plain' }));
    showTvliveResult(`<a href="${url}" download class="result-link">⬇️ 下载日志文件</a>`, 'success');
  }).catch(e => showTvliveResult(`<span class="result-error">${e.message}</span>`, 'error'));
});

// 缓存大小
document.getElementById('btnTvliveCacheSize').addEventListener('click', async () => {
  const serial = getTvliveSerial();
  if (!serial) return;
  try {
    const res = await fetch('/api/tvlive/cache-size', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ serial })
    });
    const data = await res.json();
    if (data.success) {
      let html = '<div class="perf-grid">';
      Object.entries(data.cache).forEach(([k, v]) => {
        html += `<div class="perf-item"><div class="perf-label">${k}</div><div class="perf-value" style="font-size:13px">${v}</div></div>`;
      });
      html += '</div>';
      showTvliveResult(html, 'success');
    }
  } catch (e) {
    showTvliveResult(`<span class="result-error">${e.message}</span>`, 'error');
  }
});

// 清理缓存
document.getElementById('btnTvliveClearCache').addEventListener('click', () => {
  if (confirm('确定要清理 TVLive 的所有缓存吗？')) {
    showToast('正在清理缓存...', 'info');
    tvlivePost('/api/tvlive/clear-cache');
  }
});

// WebServer
document.getElementById('btnTvliveWebServer').addEventListener('click', () => {
  const serial = getTvliveSerial();
  if (!serial) return;
  fetch('/api/tvlive/webserver', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ serial })
  }).then(r => r.json()).then(data => {
    if (data.success) {
      showTvliveResult(`<div style="padding:10px"><p style="margin-bottom:8px">${data.note}</p><p>端口: <strong>${data.port}</strong></p><p style="margin-top:10px"><em>提示: 使用「端口转发」建立连接后，通过浏览器访问 http://localhost:${data.port}</em></p></div>`, 'success');
    } else {
      showTvliveResult(`<span class="result-error">${data.error}</span>`, 'error');
    }
  });
});

// 模拟切台
document.getElementById('btnTvliveSimChannel').addEventListener('click', () => {
  showTvliveResult(`
    <div style="padding:10px">
      <p style="margin-bottom:10px">选择要模拟的操作：</p>
      <div style="display:flex;gap:8px;flex-wrap:wrap">
        <button class="sim-btn" data-act="prev" style="padding:8px 16px;background:var(--bg-primary);border:1px solid var(--border-color);border-radius:6px;color:var(--text-primary);cursor:pointer">⬅ 上一个频道</button>
        <button class="sim-btn" data-act="next" style="padding:8px 16px;background:var(--bg-primary);border:1px solid var(--border-color);border-radius:6px;color:var(--text-primary);cursor:pointer">➡ 下一个频道</button>
        <button class="sim-btn" data-act="num_1" style="padding:8px 16px;background:var(--bg-primary);border:1px solid var(--border-color);border-radius:6px;color:var(--text-primary);cursor:pointer">1号频道</button>
        <button class="sim-btn" data-act="num_2" style="padding:8px 16px;background:var(--bg-primary);border:1px solid var(--border-color);border-radius:6px;color:var(--text-primary);cursor:pointer">2号频道</button>
        <button class="sim-btn" data-act="num_3" style="padding:8px 16px;background:var(--bg-primary);border:1px solid var(--border-color);border-radius:6px;color:var(--text-primary);cursor:pointer">3号频道</button>
      </div>
    </div>
  `, 'info');
  document.querySelectorAll('.sim-btn').forEach(btn => {
    btn.onclick = () => {
      tvlivePost('/api/tvlive/simulate-channel', { channelAction: btn.dataset.act });
      document.getElementById('tvliveResult').classList.add('hidden');
    };
  });
});

// ========== 虎牙 API 监控 ==========

// 运行期凭证快照：UI 展示 + 请求 header 元数据用
// 每次调用 /api/huya/credentials 成功后会刷新此对象
window.currentHuyaCredentials = null;

function huyaHeaders(extra = {}) {
  const h = Object.assign({ 'Content-Type': 'application/json' }, extra || {});
  const snap = window.currentHuyaCredentials && window.currentHuyaCredentials.raw
    ? window.currentHuyaCredentials.raw
    : null;
  if (snap) {
    try {
      h['X-Huya-Credentials'] = btoa(unescape(encodeURIComponent(
        `g=${snap.gameId};a=${snap.appId};s=${window.currentHuyaCredentials.source || 'unknown'}`
      )));
    } catch (_) { /* 某些浏览器环境下 btoa 可能抛，忽略 */ }
  }
  return h;
}

function huyaPost(endpoint, body) {
  return fetch(endpoint, {
    method: 'POST',
    headers: huyaHeaders(),
    body: JSON.stringify(body || {})
  }).then(r => r.json());
}

function huyaGet(endpoint) {
  return fetch(endpoint, {
    headers: huyaHeaders({ 'Content-Type': null }) // GET 不需要 Content-Type
  }).then(r => r.json());
}

function showHuyaResult(html, type = 'info') {
  const result = document.getElementById('huyaResult');
  if (!result) return;
  result.innerHTML = `
    <div class="result-header">
      <span class="result-title">🐯 虎牙 API 结果</span>
      <button class="result-close" onclick="document.getElementById('huyaResult').classList.add('hidden')">✕</button>
    </div>
    <div class="result-content result-${type}">${html}</div>
  `;
  result.classList.remove('hidden');
}

function huyaSourceColorTag(source) {
  if (!source) return { label: '未知', cls: '' };
  if (source.startsWith('runtime_override') || source === 'manual_override') {
    return { label: '手动覆盖(内存)', cls: 'huya-source-tag-manual' };
  }
  if (source === 'adb_logcat_game_id_plus_static_appkeys') {
    return { label: 'ADB日志+源码合成', cls: 'huya-source-tag-adb' };
  }
  if (source === 'static_xor_decode') {
    return { label: '源码XOR静态解码', cls: 'huya-source-tag-static' };
  }
  return { label: source, cls: '' };
}

function renderHuyaCredentials(resp) {
  if (!resp || !resp.success) return;
  window.currentHuyaCredentials = resp; // 缓存快照
  const raw = resp.raw || resp.credentials || {};

  const gidEl = document.getElementById('huyaGameId');
  const aidEl = document.getElementById('huyaAppId');
  const akeyEl = document.getElementById('huyaAppKey');
  if (gidEl) gidEl.textContent = raw.gameId ?? '-';
  if (aidEl) aidEl.textContent = raw.appId ?? '-';
  if (akeyEl) akeyEl.textContent = raw.appKey ?? '-';

  const tagEl = document.getElementById('huyaSourceTag');
  if (tagEl) {
    const { label, cls } = huyaSourceColorTag(resp.source);
    tagEl.textContent = label;
    tagEl.className = 'huya-source-tag ' + (cls || '');
    tagEl.title = `来源：${resp.source || '-'}\n更新时间：${resp.updatedAt ? new Date(resp.updatedAt).toLocaleString() : '首次加载默认值'}`;
  }

  const chainRow = document.getElementById('huyaCredMeta');
  const chainEl = document.getElementById('huyaSourceChain');
  if (chainRow && chainEl) {
    const chain = Array.isArray(resp.sourceChain) ? resp.sourceChain : [];
    if (chain.length > 0) {
      chainEl.innerHTML = chain.map((c, i) =>
        `<span class="chain-step"><span class="chain-idx">${i + 1}</span>${escapeHtml(c)}</span>`
      ).join('');
      chainRow.style.display = '';
    } else {
      chainRow.style.display = 'none';
    }
  }

  const devRow = document.getElementById('huyaCredDeviceRow');
  const devEl = document.getElementById('huyaDeviceSummary');
  if (devRow && devEl) {
    const hasLog = !!(resp.device && resp.device.logcat);
    const hasPrefs = !!(resp.device && resp.device.encryptedPrefs && resp.device.encryptedPrefs.exists);
    if (resp.deviceSerial || hasLog || hasPrefs) {
      const parts = [];
      if (resp.deviceSerial) parts.push(`<b>ADB:</b> ${escapeHtml(String(resp.deviceSerial).slice(0, 32))}${String(resp.deviceSerial).length > 32 ? '…' : ''}`);
      if (hasLog) {
        const lc = resp.device.logcat;
        const tag = lc.usedDefaultFallbackOnDevice ? '默认值兜底' : (lc.deviceInitialized ? '设备已init凭证' : '未检测');
        parts.push(`<b>Logcat:</b> ${tag}` + (lc.gameId ? ` (gameId=${lc.gameId})` : ''));
      }
      if (hasPrefs) parts.push(`<b>EncryptedStorage:</b> XML已存在(AES密文，Keystore密钥不可导出)`);
      devEl.innerHTML = parts.join(' ｜ ');
      devRow.style.display = '';
    } else {
      devRow.style.display = 'none';
    }
  }
}

async function loadHuyaCredentials(autoScan = true) {
  try {
    const url = '/api/huya/credentials?autoScan=' + (autoScan ? '1' : '0');
    const data = await huyaGet(url);
    if (data && data.success) {
      renderHuyaCredentials(data);
      return data;
    } else if (data) {
      showToast('获取虎牙凭证失败: ' + (data.error || 'unknown'), 'error');
    }
  } catch (e) {
    showToast('获取虎牙凭证异常: ' + e.message, 'error');
  }
  return null;
}

async function saveHuyaCredentialsManual() {
  const gameId = document.getElementById('manualHuyaGameId').value.trim();
  const appId = document.getElementById('manualHuyaAppId').value.trim();
  const appKey = document.getElementById('manualHuyaAppKey').value.trim();
  if (!gameId || !appId || !appKey) {
    showToast('请填写完整的 Game ID / App ID / App Key', 'error');
    return;
  }
  try {
    const resp = await huyaPost('/api/huya/credentials', {
      gameId: Number(gameId), appId, appKey,
      source: 'manual_override_from_ui'
    });
    if (resp && resp.success) {
      showToast('凭证已写入运行期缓存（重启失效）', 'success');
      document.getElementById('manualHuyaGameId').value = '';
      document.getElementById('manualHuyaAppId').value = '';
      document.getElementById('manualHuyaAppKey').value = '';
      // 刷新一次 UI
      await loadHuyaCredentials(false);
    } else {
      showToast('保存失败: ' + (resp && resp.error ? resp.error : 'unknown'), 'error');
    }
  } catch (e) {
    showToast('保存异常: ' + e.message, 'error');
  }
}

// 加载凭证信息（点击👁️显示/隐藏 + 每次展开刷新一次）
document.getElementById('btnHuyaCredToggle').addEventListener('click', async () => {
  const body = document.getElementById('huyaCredBody');
  if (body.classList.contains('hidden')) {
    // 展开前先刷新
    await loadHuyaCredentials(true);
    body.classList.remove('hidden');
  } else {
    body.classList.add('hidden');
  }
});

// 重新获取按钮
const btnRefresh = document.getElementById('btnHuyaCredRefresh');
if (btnRefresh) btnRefresh.addEventListener('click', () => loadHuyaCredentials(true));

// 手动保存按钮
const btnSave = document.getElementById('btnHuyaCredSave');
if (btnSave) btnSave.addEventListener('click', saveHuyaCredentialsManual);

// 页面加载完成后，后台静默拉一次默认凭证（用于请求 header 元数据）
window.addEventListener('load', function () {
  // 延迟 1 秒，避免与 ADB 初始化/WS 握手抢主线程
  setTimeout(() => loadHuyaCredentials(true).catch(() => { /* silent */ }), 1000);
});

// API 健康检查
document.getElementById('btnHuyaHealthCheck').addEventListener('click', async () => {
  const body = document.getElementById('huyaHealthBody');
  body.innerHTML = '<p class="loading">检测中...</p>';
  try {
    const data = await huyaGet('/api/huya/health');
    if (data.success) {
      let html = '<div class="huya-health-grid">';
      data.results.forEach(r => {
        const statusColor = r.ok ? '#48bb78' : '#f56565';
        html += `<div class="health-item" style="border-left: 3px solid ${statusColor}">
          <div class="health-name">${r.name}</div>
          <div class="health-status" style="color:${statusColor}">${r.ok ? '✓ 正常' : '✗ 异常'}</div>
          <div class="health-duration">${r.duration}ms</div>
          ${r.error ? `<div class="health-error">${r.error}</div>` : ''}
        </div>`;
      });
      html += '</div>';
      html += `<div class="health-summary">正常: ${data.summary.healthy} / ${data.summary.total}</div>`;
      body.innerHTML = html;
    }
  } catch (e) {
    body.innerHTML = `<p class="error">检测失败: ${e.message}</p>`;
  }
});

// 房间解析
document.getElementById('btnHuyaParse').addEventListener('click', async () => {
  const roomId = parseInt(document.getElementById('huyaRoomId').value);
  const method = document.getElementById('huyaParseMethod').value;
  if (!roomId) { showToast('请输入房间号', 'error'); return; }
  showToast('正在解析房间...', 'info');
  try {
    const data = await huyaPost('/api/huya/parse-room', { roomId, method });
    if (data.success) {
      let html = '<div class="parse-result">';
      html += `<div class="parse-info"><strong>房间:</strong> ${data.roomId} | <strong>耗时:</strong> ${data.duration}ms</div>`;
      html += `<div class="parse-source">来源: ${data.result.source}</div>`;
      if (data.result.hlsUrl) html += `<div class="parse-url"><span class="url-label">HLS:</span> <a href="${data.result.hlsUrl}" target="_blank">${data.result.hlsUrl.substring(0, 60)}...</a></div>`;
      if (data.result.flvUrl) html += `<div class="parse-url"><span class="url-label">FLV:</span> <a href="${data.result.flvUrl}" target="_blank">${data.result.flvUrl.substring(0, 60)}...</a></div>`;
      if (data.rawResponse) html += `<details class="parse-raw"><summary>原始响应</summary><pre>${JSON.stringify(data.result.rawResponse, null, 2).substring(0, 1000)}</pre></details>`;
      html += '</div>';
      showHuyaResult(html, 'success');
    } else {
      showHuyaResult(`<span class="result-error">❌ ${data.error || '解析失败'}</span>`, 'error');
    }
  } catch (e) {
    showHuyaResult(`<span class="result-error">❌ ${e.message}</span>`, 'error');
  }
});

// 获取一起看频道
document.getElementById('btnHuyaFetchChannels').addEventListener('click', async () => {
  const category = document.getElementById('huyaChannelCategory').value;
  showToast('正在获取频道...', 'info');
  try {
    const data = await huyaPost('/api/huya/together-watch', { category, page: 1, pageSize: 10 });
    if (data.success) {
      let html = `<div class="channel-result"><div class="channel-info">分类: ${data.category} | 频道数: ${data.total}</div>`;
      if (data.channels.length > 0) {
        html += '<div class="channel-list">';
        data.channels.slice(0, 30).forEach(ch => {
          html += `<div class="channel-item">
            <div class="channel-name">${ch.roomName}</div>
            <div class="channel-meta">
              <span>ID: ${ch.roomId}</span>
              <span>${ch.onlineCount > 0 ? ch.onlineCount + ' 在线' : '离线'}</span>
              <span class="channel-live ${ch.isLive ? 'live' : ''}">${ch.isLive ? '● 直播中' : '○'}</span>
            </div>
          </div>`;
        });
        html += '</div>';
        if (data.total > 30) html += `<div class="channel-more">仅显示前 30 个，共 ${data.total} 个</div>`;
      } else {
        html += '<p>暂无频道数据</p>';
      }
      html += '</div>';
      showHuyaResult(html, 'success');
    } else {
      showHuyaResult(`<span class="result-error">❌ ${data.error}</span>`, 'error');
    }
  } catch (e) {
    showHuyaResult(`<span class="result-error">❌ ${e.message}</span>`, 'error');
  }
});

// DNS 检测
document.getElementById('btnHuyaDnsCheck').addEventListener('click', async () => {
  showToast('正在检测 DNS...', 'info');
  try {
    const data = await huyaGet('/api/huya/dns');
    if (data.success) {
      let html = '<div class="dns-result">';
      data.results.forEach(r => {
        const color = r.reachable ? '#48bb78' : '#f56565';
        html += `<div class="dns-item" style="border-left: 3px solid ${color}">
          <div class="dns-domain">${r.domain}</div>
          <div class="dns-status" style="color:${color}">${r.reachable ? '✓ 可达' : '✗ 不可达'}</div>
          <div class="dns-duration">${r.duration}ms</div>
        </div>`;
      });
      html += '</div>';
      showHuyaResult(html, 'success');
    }
  } catch (e) {
    showHuyaResult(`<span class="result-error">❌ ${e.message}</span>`, 'error');
  }
});

// CDN 检测
document.getElementById('btnHuyaCdnCheck').addEventListener('click', async () => {
  showToast('正在检测 CDN...', 'info');
  try {
    const data = await huyaGet('/api/huya/cdn-check');
    if (data.success) {
      let html = '<div class="cdn-result">';
      data.results.forEach(r => {
        const color = r.reachable ? '#48bb78' : '#f56565';
        html += `<div class="cdn-item" style="border-left: 3px solid ${color}">
          <div class="cdn-host">${r.host}</div>
          <div class="cdn-status" style="color:${color}">${r.reachable ? '✓ 在线' : '✗ 离线'}</div>
          <div class="cdn-duration">${r.duration}ms</div>
        </div>`;
      });
      html += '</div>';
      showHuyaResult(html, 'success');
    }
  } catch (e) {
    showHuyaResult(`<span class="result-error">❌ ${e.message}</span>`, 'error');
  }
});

// Cache API
document.getElementById('btnHuyaCacheList').addEventListener('click', async () => {
  showToast('正在获取 Cache 列表...', 'info');
  try {
    const data = await huyaPost('/api/huya/cache-list', { gameId: 2135, page: 1 });
    if (data.success) {
      let html = `<div class="cache-result"><div class="cache-info">频道数: ${data.total}</div>`;
      if (data.channels.length > 0) {
        html += '<div class="channel-list">';
        data.channels.slice(0, 20).forEach(ch => {
          html += `<div class="channel-item">
            <div class="channel-name">${ch.roomName}</div>
            <div class="channel-meta">
              <span>ID: ${ch.roomId}</span>
              <span>${ch.onlineCount > 0 ? ch.onlineCount + ' 在线' : '离线'}</span>
            </div>
          </div>`;
        });
        html += '</div>';
      }
      html += '</div>';
      showHuyaResult(html, 'success');
    } else {
      showHuyaResult(`<span class="result-error">❌ ${data.error}</span>`, 'error');
    }
  } catch (e) {
    showHuyaResult(`<span class="result-error">❌ ${e.message}</span>`, 'error');
  }
});

// API 历史
async function refreshHuyaHistory() {
  try {
    const data = await huyaGet('/api/huya/history?limit=20');
    if (data.success) {
      document.getElementById('huyaTotalCalls').textContent = data.stats.total;
      document.getElementById('huyaAvgDuration').textContent = data.stats.avgDuration;
      document.getElementById('huyaErrorRate').textContent = data.stats.errorRate;

      // 显示各端点统计
      if (data.history.length > 0) {
        let html = '<div class="history-list">';
        data.history.forEach(entry => {
          const time = formatTime(entry.timestamp);
          const statusColor = entry.status >= 400 || entry.status === 0 ? '#f56565' : '#48bb78';
          html += `<div class="history-item">
            <span class="history-time">${time}</span>
            <span class="history-endpoint">${entry.endpoint}</span>
            <span class="history-status" style="color:${statusColor}">${entry.status}</span>
            <span class="history-duration">${entry.duration}ms</span>
          </div>`;
        });
        html += '</div>';
        document.getElementById('huyaHistoryList').innerHTML = html;
      }
    }
  } catch (e) {
    console.error('刷新历史失败:', e);
  }
}

document.getElementById('btnHuyaRefreshHistory').addEventListener('click', refreshHuyaHistory);
document.getElementById('btnHuyaClearHistory').addEventListener('click', async () => {
  try {
    await fetch('/api/huya/history', { method: 'DELETE' });
    refreshHuyaHistory();
    showToast('历史已清除', 'success');
  } catch (e) {
    showToast('清除失败', 'error');
  }
});

// 定期刷新历史
setInterval(refreshHuyaHistory, 10000);
refreshHuyaHistory();

// ========== 云端连接功能 ==========

let cloudConnected = false;
let cloudServerUrl = '';
let cloudHeartbeatTimer = null;
let cloudReconnectTimer = null;
let cloudReconnectCount = 0;
const MAX_RECONNECT_COUNT = 10;

// 云端标签页切换
document.querySelectorAll('.panel-tab').forEach(tab => {
  tab.addEventListener('click', () => {
    document.querySelectorAll('.panel-tab').forEach(t => t.classList.remove('active'));
    document.querySelectorAll('.tab-content').forEach(c => c.classList.add('hidden'));
    tab.classList.add('active');
    const tabId = tab.dataset.tab;
    document.getElementById(tabId + 'Tab').classList.remove('hidden');
    
    if (tabId === 'cloud') {
      loadCloudStatus();
    }
  });
});

// 加载云端状态
async function loadCloudStatus() {
  try {
    const response = await fetch('/api/cloud/status');
    const status = await response.json();
    
    if (status.connected) {
      showCloudConnected(status.serverUrl, status.lastSyncTime);
    } else {
      showCloudDisconnected();
    }
  } catch (e) {
    showCloudDisconnected();
  }
}

// 显示云端已连接
function showCloudConnected(serverUrl, lastSyncTime) {
  cloudConnected = true;
  cloudServerUrl = serverUrl;
  
  document.getElementById('cloudStatus').classList.add('connected');
  document.getElementById('cloudStatusText').textContent = '已连接到云端服务器';
  document.getElementById('cloudConnectForm').classList.add('hidden');
  document.getElementById('cloudConnectedPanel').classList.remove('hidden');
  document.getElementById('cloudServerDisplay').textContent = serverUrl;
  
  if (lastSyncTime > 0) {
    const date = new Date(lastSyncTime);
    document.getElementById('cloudLastSync').textContent = formatTime(lastSyncTime);
  } else {
    document.getElementById('cloudLastSync').textContent = '从未';
  }
  
  loadCloudDevices();
}

// 显示云端未连接
function showCloudDisconnected() {
  cloudConnected = false;
  
  document.getElementById('cloudStatus').classList.remove('connected');
  document.getElementById('cloudStatusText').textContent = '未连接到云端服务器';
  document.getElementById('cloudConnectForm').classList.remove('hidden');
  document.getElementById('cloudConnectedPanel').classList.add('hidden');
}

// 加载云端设备列表
async function loadCloudDevices() {
  try {
    const response = await fetch('/api/cloud/devices');
    const result = await response.json();
    
    const deviceList = document.getElementById('cloudDeviceList');
    if (result.success && result.devices && result.devices.length > 0) {
      deviceList.innerHTML = '';
      result.devices.forEach(device => {
        const item = document.createElement('div');
        item.className = 'cloud-device-item';
        item.innerHTML = `
          <div class="cloud-device-info">
            <span class="cloud-device-name">${device.deviceName || device.info?.deviceName || '未知设备'}</span>
            <span class="cloud-device-id">ID: ${device.deviceId || device.id || 'Unknown'}</span>
          </div>
          <span class="cloud-device-status">在线</span>
        `;
        deviceList.appendChild(item);
      });
    } else {
      deviceList.innerHTML = '<p class="empty-state">暂无在线设备</p>';
    }
  } catch (e) {
    document.getElementById('cloudDeviceList').innerHTML = '<p class="empty-state">加载失败</p>';
  }
}

// 连接云端服务器表单提交
document.getElementById('cloudConnectForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  
  const url = document.getElementById('cloudServerUrl').value.trim();
  const apiKey = document.getElementById('cloudApiKey').value.trim();
  const btn = document.getElementById('cloudConnectBtn');
  
  if (!url) {
    showToast('请输入云端服务器地址', 'error');
    return;
  }
  
  // 去除末尾斜杠
  const serverUrl = url.replace(/\/+$/, '');
  
  btn.disabled = true;
  const originalText = btn.querySelector('span:last-child').textContent;
  btn.querySelector('span:last-child').textContent = '连接中...';
  
  try {
    // 先通过 HTTP 检查服务器状态
    const statusRes = await fetch(serverUrl + '/api/status');
    const statusData = await statusRes.json();
    
    if (statusData.status !== 'ok') {
      throw new Error('服务器状态异常');
    }
    
    // 建立 WebSocket 连接
    const wsUrl = serverUrl.replace('http://', 'ws://').replace('https://', 'wss://') + '/ws';
    
    if (window.cloudWS) {
      try { window.cloudWS.close(); } catch(e) {}
    }
    
    window.cloudWS = new WebSocket(wsUrl);
    
    window.cloudWS.onopen = function() {
      showToast('已连接到云端服务器', 'success');
      showCloudConnected(serverUrl, Date.now());
      cloudReconnectCount = 0;
      
      // 请求设备列表
      window.cloudWS.send(JSON.stringify({ type: 'get_devices' }));
      
      // 保存 serverUrl
      window._lastCloudUrl = serverUrl;
      
      // 延迟加载历史日志
      setTimeout(function() {
        const url = window._lastCloudUrl || cloudServerUrl;
        try {
          const xhr = new XMLHttpRequest();
          xhr.open('GET', url + '/api/logs?limit=500', false);
          xhr.send();
          if (xhr.status === 200) {
            const data = JSON.parse(xhr.responseText);
            if (data.logs && data.logs.length > 0) {
              data.logs.forEach(log => addLog(log));
              showToast('已加载 ' + data.logs.length + ' 条历史日志', 'info');
            }
            updateStats();
          }
        } catch(e) {
          console.error('加载历史日志失败:', e);
        }
      }, 200);
      
      // 启动心跳机制（每 30 秒发送一次 ping）
      startCloudHeartbeat();
    };
    
    window.cloudWS.onmessage = function(event) {
      try {
        const msg = JSON.parse(event.data);
        handleCloudWSMessage(msg);
      } catch(e) {
        console.error('Cloud WS parse error:', e);
      }
    };
    
    window.cloudWS.onclose = function() {
      stopCloudHeartbeat();
      const wasConnected = cloudConnected;
      showCloudDisconnected();
      
      if (wasConnected) {
        showToast('云端连接已断开', 'warning');
        // 自动重连
        attemptCloudReconnect();
      }
    };
    
    window.cloudWS.onerror = function() {
      // 错误不直接断开，等 onclose 处理
    };
    
  } catch (e) {
    showToast('连接失败: ' + e.message, 'error');
  } finally {
    btn.disabled = false;
    btn.querySelector('span:last-child').textContent = originalText;
  }
});

// 处理云端 WebSocket 消息
function handleCloudWSMessage(msg) {
  switch(msg.type) {
    case 'connected':
      console.log('Cloud connected:', msg.message);
      break;
      
    case 'devices_list':
      // 更新设备列表
      updateCloudDevices(msg.devices || []);
      break;
      
    case 'history_logs':
      // 加载历史日志
      if (msg.logs && msg.logs.length > 0) {
        msg.logs.forEach(log => {
          addLog(log);
        });
        showToast('已加载 ' + msg.logs.length + ' 条历史日志', 'info');
      }
      break;
      
    case 'logs_batch':
      // 实时日志推送
      if (msg.logs && msg.logs.length > 0) {
        msg.logs.forEach(log => {
          addLog(log);
        });
      }
      break;
      
    case 'logs_cleared':
      showToast('设备日志已清空', 'info');
      break;
      
    case 'all_logs_cleared':
      clearAllLogs();
      showToast('所有日志已清空', 'info');
      break;
      
    case 'pong':
      // 心跳响应
      break;
  }
}

// 更新云端设备列表
function updateCloudDevices(devices) {
  const deviceList = document.getElementById('cloudDeviceList');
  if (!deviceList) return;
  
  if (devices.length === 0) {
    deviceList.innerHTML = '<p class="empty-state">暂无在线设备</p>';
    return;
  }
  
  deviceList.innerHTML = '';
  devices.forEach(device => {
    const info = device.info || device;
    const item = document.createElement('div');
    item.className = 'cloud-device-item';
    item.innerHTML = `
      <div class="cloud-device-info">
        <span class="cloud-device-name">${info.deviceName || '未知设备'}</span>
        <span class="cloud-device-id">ID: ${device.id || info.deviceId || 'Unknown'}</span>
      </div>
      <span class="cloud-device-status">${device.connected ? '在线' : '离线'}</span>
    `;
    deviceList.appendChild(item);
  });
  
  // 更新顶部状态
  const statusText = document.querySelector('.status-bar span');
  if (statusText) {
    statusText.textContent = `已连接 | 设备数: ${devices.length} | 日志数: ${logs.length}`;
  }
}

// 断开云端连接
document.getElementById('cloudDisconnectBtn').addEventListener('click', async () => {
  if (!confirm('确定要断开云端服务器连接吗？')) return;
  
  // 停止心跳和重连
  stopCloudHeartbeat();
  if (cloudReconnectTimer) {
    clearTimeout(cloudReconnectTimer);
    cloudReconnectTimer = null;
  }
  cloudReconnectCount = MAX_RECONNECT_COUNT; // 防止自动重连
  
  // 关闭 WebSocket
  if (window.cloudWS) {
    try { window.cloudWS.close(); } catch(e) {}
  }
  
  showToast('已断开云端连接', 'success');
  showCloudDisconnected();
});

// 手动同步云端日志
document.getElementById('cloudSyncBtn').addEventListener('click', async () => {
  const btn = document.getElementById('cloudSyncBtn');
  btn.disabled = true;
  const originalText = btn.querySelector('span:last-child').textContent;
  btn.querySelector('span:last-child').textContent = '同步中...';
  
  try {
    if (!cloudServerUrl) {
      showToast('请先连接云端服务器', 'warning');
      return;
    }
    
    const response = await fetch(cloudServerUrl + '/api/logs?limit=500');
    const result = await response.json();
    
    if (result.logs && result.logs.length > 0) {
      // 添加新日志
      let newCount = 0;
      for (const log of result.logs) {
        // 检查是否已存在（简单去重）
        if (!logs.some(l => l.timestamp === log.timestamp && l.message === log.message)) {
          addLog(log);
          newCount++;
        }
      }
      showToast(`已同步 ${newCount} 条新日志`, 'success');
    } else {
      showToast('没有新日志', 'info');
    }
    
    // 更新最后同步时间
    const now = formatTime(Date.now());
    document.getElementById('cloudLastSync').textContent = now;
    
    // 刷新设备列表
    if (window.cloudWS && window.cloudWS.readyState === WebSocket.OPEN) {
      window.cloudWS.send(JSON.stringify({ type: 'get_devices' }));
    }
  } catch (e) {
    showToast('同步失败: ' + e.message, 'error');
  } finally {
    btn.disabled = false;
    btn.querySelector('span:last-child').textContent = originalText;
  }
});

// 从服务器加载历史日志
function loadHistoryLogs(serverUrl) {
  try {
    const xhr = new XMLHttpRequest();
    xhr.open('GET', serverUrl + '/api/logs?limit=500', true);
    xhr.onload = function() {
      if (xhr.status === 200) {
        try {
          const data = JSON.parse(xhr.responseText);
          if (data.logs && data.logs.length > 0) {
            data.logs.forEach(log => addLog(log));
            showToast('已加载 ' + data.logs.length + ' 条历史日志', 'info');
          } else {
            showToast('暂无历史日志', 'info');
          }
          updateStats();
        } catch(e) {
          console.error('解析日志数据失败:', e);
        }
      }
    };
    xhr.onerror = function() {
      console.error('加载历史日志失败: 网络错误');
      showToast('加载历史日志失败', 'error');
    };
    xhr.send();
  } catch(err) {
    console.error('加载历史日志异常:', err);
  }
}

// 启动心跳机制
function startCloudHeartbeat() {
  stopCloudHeartbeat();
  cloudHeartbeatTimer = setInterval(() => {
    if (window.cloudWS && window.cloudWS.readyState === WebSocket.OPEN) {
      window.cloudWS.send(JSON.stringify({ type: 'ping', timestamp: Date.now() }));
    }
  }, 30000); // 每 30 秒发送一次
}

// 停止心跳机制
function stopCloudHeartbeat() {
  if (cloudHeartbeatTimer) {
    clearInterval(cloudHeartbeatTimer);
    cloudHeartbeatTimer = null;
  }
}

// 尝试自动重连
function attemptCloudReconnect() {
  if (cloudReconnectTimer) {
    clearTimeout(cloudReconnectTimer);
  }
  
  if (cloudReconnectCount >= MAX_RECONNECT_COUNT) {
    showToast('重连次数已达上限，请手动重新连接', 'error');
    return;
  }
  
  cloudReconnectCount++;
  const delay = Math.min(1000 * Math.pow(2, cloudReconnectCount - 1), 30000);
  showToast(`正在尝试重连 (${cloudReconnectCount}/${MAX_RECONNECT_COUNT})...`, 'info');
  
  cloudReconnectTimer = setTimeout(() => {
    if (cloudServerUrl) {
      try {
        const wsUrl = cloudServerUrl.replace('http://', 'ws://').replace('https://', 'wss://') + '/ws';
        
        if (window.cloudWS) {
          try { window.cloudWS.close(); } catch(e) {}
        }
        
        window.cloudWS = new WebSocket(wsUrl);
        
        window.cloudWS.onopen = function() {
          showToast('重连成功', 'success');
          showCloudConnected(cloudServerUrl, Date.now());
          cloudReconnectCount = 0;
          
          // 重新加载历史日志
          loadHistoryLogs(cloudServerUrl);
          
          // 启动心跳
          startCloudHeartbeat();
        };
        
        window.cloudWS.onmessage = function(event) {
          try {
            const msg = JSON.parse(event.data);
            handleCloudWSMessage(msg);
          } catch(e) {
            console.error('Cloud WS parse error:', e);
          }
        };
        
        window.cloudWS.onclose = function() {
          stopCloudHeartbeat();
          const wasConnected = cloudConnected;
          showCloudDisconnected();
          
          if (wasConnected && cloudReconnectCount < MAX_RECONNECT_COUNT) {
            attemptCloudReconnect();
          }
        };
        
      } catch (e) {
        console.error('重连失败:', e);
        attemptCloudReconnect();
      }
    }
  }, delay);
}

// ========== 日志导出功能增强 ==========

// 关键词高亮配置
let highlightConfig = {
  enabled: true,
  rules: {
    error: { enabled: true, keywords: ['error', 'fail', 'exception', 'failed', 'failure'] },
    warn: { enabled: true, keywords: ['warning', 'warn', 'deprecated', 'caution'] },
    crash: { enabled: true, keywords: ['crash', 'fatal', 'ANR', 'app not responding'] },
    network: { enabled: true, keywords: ['timeout', 'connection', 'network', 'offline', 'unreachable'] },
    performance: { enabled: false, keywords: ['slow', 'lag', 'jank', 'stall', 'stutter', 'dropped frame'] }
  },
  customKeywords: [] // [{word, color}]
};

// 加载高亮配置
function loadHighlightConfig() {
  const saved = localStorage.getItem('highlightConfig');
  if (saved) {
    try {
      highlightConfig = JSON.parse(saved);
    } catch (e) {
      // 使用默认配置
    }
  }
}

// 保存高亮配置
function saveHighlightConfig() {
  localStorage.setItem('highlightConfig', JSON.stringify(highlightConfig));
}

// 高亮关键词处理
function highlightText(text, logType) {
  if (!highlightConfig.enabled || !text) return text;
  
  let result = escapeHtml(text);
  const highlightRules = [];
  
  // 添加基于日志类型的高亮
  if (logType === 'error' || logType === 'crash') {
    highlightRules.push({ keywords: highlightConfig.rules.error.keywords, className: 'hl-error' });
    highlightRules.push({ keywords: highlightConfig.rules.crash.keywords, className: 'hl-crash' });
  } else if (logType === 'warn') {
    highlightRules.push({ keywords: highlightConfig.rules.warn.keywords, className: 'hl-warn' });
  } else if (logType === 'network') {
    highlightRules.push({ keywords: highlightConfig.rules.network.keywords, className: 'hl-network' });
  }
  
  // 添加启用的规则
  Object.entries(highlightConfig.rules).forEach(([key, rule]) => {
    if (rule.enabled && rule.keywords && rule.keywords.length > 0) {
      const className = `hl-${key}`;
      highlightRules.push({ keywords: rule.keywords, className });
    }
  });
  
  // 添加自定义关键词
  highlightConfig.customKeywords.forEach(({ word, color }) => {
    if (word) {
      highlightRules.push({ keywords: [word], className: 'hl-custom', color });
    }
  });
  
  // 应用高亮
  highlightRules.forEach(({ keywords, className, color }) => {
    keywords.forEach(keyword => {
      if (!keyword) return;
      const escapedKeyword = keyword.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
      const regex = new RegExp(`(${escapedKeyword})`, 'gi');
      result = result.replace(regex, (match) => {
        const style = color ? ` style="background:${color}30;color:${color};"` : '';
        return `<span class="${className}"${style}>${match}</span>`;
      });
    });
  });
  
  return result;
}

// 重写 createLogElement 以支持高亮
function createLogElement(log) {
  const item = document.createElement('div');
  const logType = log.logType || log.type || 'info';
  item.className = `log-item ${logType}`;

  const timestamp = document.createElement('span');
  timestamp.className = 'log-timestamp';
  timestamp.textContent = formatTime(log.timestamp || log.serverTime);

  const device = document.createElement('span');
  device.className = 'log-device';
  const deviceName = getDeviceDisplayName(log.deviceId);
  device.textContent = deviceName;
  device.title = deviceName;

  const typeBadge = document.createElement('span');
  typeBadge.className = `log-type-badge type-${logType}`;
  typeBadge.textContent = typeLabels[logType] || logType;

  const tag = document.createElement('span');
  tag.className = 'log-tag';
  tag.textContent = log.tag ? `[${log.tag}]` : '';

  const message = document.createElement('span');
  message.className = 'log-message';
  if (logType === 'crash' || log.type === 'crash') {
    const pre = document.createElement('pre');
    const highlightedText = highlightText(log.message || log.stackTrace || '', 'crash');
    pre.innerHTML = highlightedText;
    pre.style.background = 'var(--bg-secondary)';
    pre.style.padding = '8px';
    pre.style.borderRadius = '4px';
    pre.style.whiteSpace = 'pre-wrap';
    pre.style.fontSize = '11px';
    message.appendChild(pre);
  } else {
    const msgText = log.message || '';
    message.innerHTML = highlightText(msgText, logType);
  }

  item.appendChild(timestamp);
  item.appendChild(device);
  item.appendChild(typeBadge);
  item.appendChild(tag);
  item.appendChild(message);
  return item;
}

// 导出对话框相关
const exportModal = document.getElementById('exportModal');
const highlightModal = document.getElementById('highlightKeywordsModal');

// 更新导出设备列表
function updateExportDeviceFilter() {
  const exportDevice = document.getElementById('exportDevice');
  exportDevice.innerHTML = '<option value="">全部设备</option>';
  for (const [id, device] of devices) {
    const info = device.info || {};
    const displayName = info.deviceName || info.deviceModel || info.ip || id;
    const option = document.createElement('option');
    option.value = id;
    option.textContent = displayName;
    exportDevice.appendChild(option);
  }
}

// 计算导日志数量
function calculateExportCount() {
  const type = document.getElementById('exportType').value;
  const deviceId = document.getElementById('exportDevice').value;
  const timeRange = parseInt(document.getElementById('exportTimeRange').value);
  const maxCount = parseInt(document.getElementById('exportMaxCount').value);
  
  let filtered = logs;
  
  if (type) filtered = filtered.filter(l => (l.logType || l.type) === type);
  if (deviceId) filtered = filtered.filter(l => l.deviceId === deviceId);
  if (timeRange > 0) {
    const cutoffTime = Date.now() - (timeRange * 1000);
    filtered = filtered.filter(l => {
      const ts = l.timestamp || l.serverTime || 0;
      return ts >= cutoffTime;
    });
  }
  
  const count = Math.min(filtered.length, maxCount);
  document.getElementById('exportCount').textContent = `预计导出: ${count} 条日志`;
  return { filtered, count };
}

// 导出为 JSON
function exportAsJSON(logsToExport, options) {
  const exportData = {
    exportedAt: new Date().toISOString(),
    totalCount: logsToExport.length,
    options: {
      includeMetadata: options.includeMetadata,
      includeTimestamp: options.includeTimestamp,
      sorted: options.sorted
    },
    logs: logsToExport.map(log => {
      const entry = {};
      if (options.includeTimestamp) {
        entry.timestamp = log.timestamp || log.serverTime;
        entry.formattedTime = formatTime(log.timestamp || log.serverTime);
      }
      entry.deviceId = log.deviceId;
      entry.logType = log.logType || log.type;
      entry.tag = log.tag;
      entry.message = log.message || log.stackTrace || '';
      
      if (options.includeMetadata) {
        const device = devices.get(log.deviceId);
        if (device && device.info) {
          entry.deviceInfo = {
            name: device.info.deviceName,
            model: device.info.deviceModel,
            version: device.info.appVersion,
            ip: device.info.ip
          };
        }
      }
      return entry;
    })
  };
  
  if (options.sorted) {
    exportData.logs.sort((a, b) => (a.timestamp || 0) - (b.timestamp || 0));
  }
  
  return JSON.stringify(exportData, null, 2);
}

// 导出为 CSV
function exportAsCSV(logsToExport, options) {
  const headers = ['时间', '设备', '类型', '标签', '消息'];
  if (options.includeMetadata) {
    headers.push('设备型号', '应用版本', 'IP地址');
  }
  
  const rows = [headers.join(',')];
  
  logsToExport.forEach(log => {
    const row = [
      formatTime(log.timestamp || log.serverTime),
      getDeviceDisplayName(log.deviceId),
      log.logType || log.type || '',
      log.tag || '',
      (log.message || log.stackTrace || '').replace(/"/g, '""')
    ];
    
    if (options.includeMetadata) {
      const device = devices.get(log.deviceId);
      const info = device ? device.info : {};
      row.push(info.deviceModel || '');
      row.push(info.appVersion || '');
      row.push(info.ip || '');
    }
    
    rows.push(row.map(cell => `"${cell}"`).join(','));
  });
  
  return '\uFEFF' + rows.join('\n');
}

// 导出为 TXT
function exportAsTXT(logsToExport, options) {
  let content = `TV Live 日志导出\n`;
  content += `导出时间: ${formatDateTime(Date.now())}\n`;
  content += `日志数量: ${logsToExport.length}\n`;
  content += `${'='.repeat(60)}\n\n`;
  
  const sorted = options.sorted 
    ? [...logsToExport].sort((a, b) => (a.timestamp || 0) - (b.timestamp || 0))
    : logsToExport;
  
  sorted.forEach(log => {
    const time = formatTime(log.timestamp || log.serverTime);
    const device = getDeviceDisplayName(log.deviceId);
    const type = typeLabels[log.logType || log.type] || log.type || '';
    const tag = log.tag ? `[${log.tag}]` : '';
    const message = log.message || log.stackTrace || '';
    
    content += `[${time}] ${device} ${type} ${tag} ${message}\n`;
    
    if (options.includeMetadata) {
      const deviceInfo = devices.get(log.deviceId);
      if (deviceInfo && deviceInfo.info) {
        const info = deviceInfo.info;
        if (info.deviceModel) content += `    设备: ${info.deviceModel}\n`;
        if (info.appVersion) content += `    版本: ${info.appVersion}\n`;
        if (info.ip) content += `    IP: ${info.ip}\n`;
      }
    }
  });
  
  return content;
}

// 触发下载
function triggerDownload(content, filename, mimeType) {
  const blob = new Blob([content], { type: mimeType });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  a.rel = 'noopener';
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  setTimeout(() => URL.revokeObjectURL(url), 5000);
}

// 执行导出
function executeExport() {
  try {
    const format = document.querySelector('input[name="exportFormat"]:checked').value;
    const { filtered, count } = calculateExportCount();
    const logsToExport = filtered.slice(0, count);
    
    if (logsToExport.length === 0) {
      showToast('没有可导出的日志，请调整筛选条件', 'warn');
      return;
    }
    
    const options = {
      includeMetadata: document.getElementById('exportIncludeMetadata').checked,
      includeTimestamp: document.getElementById('exportIncludeTimestamp').checked,
      sorted: document.getElementById('exportSorted').checked
    };
    
    const timestamp = new Date().toISOString().replace(/[:.]/g, '-');
    let content = '';
    let mimeType = 'text/plain';
    let filename = `tv-live-logs-${timestamp}.txt`;
    
    switch (format) {
      case 'json':
        content = exportAsJSON(logsToExport, options);
        mimeType = 'application/json';
        filename = `tv-live-logs-${timestamp}.json`;
        break;
      case 'csv':
        content = exportAsCSV(logsToExport, options);
        mimeType = 'text/csv;charset=utf-8';
        filename = `tv-live-logs-${timestamp}.csv`;
        break;
      case 'txt':
        content = exportAsTXT(logsToExport, options);
        mimeType = 'text/plain;charset=utf-8';
        filename = `tv-live-logs-${timestamp}.txt`;
        break;
    }
    
    if (!content || content.length === 0) {
      console.error('Export content is empty!', { format, count, logsToExportLength: logsToExport.length });
      showToast('导出内容为空，请检查日志数据', 'error');
      return;
    }
    
    console.log(`Exporting ${count} logs as ${format}, content size: ${content.length} bytes`);
    triggerDownload(content, filename, mimeType);
    showToast(`已导出 ${count} 条日志为 ${format.toUpperCase()} 格式`, 'success');
    closeExportModalFn();
  } catch (err) {
    console.error('Export failed:', err);
    showToast('导出失败: ' + err.message, 'error');
  }
}

// 打开导出对话框
function openExportModalFn() {
  exportModal.classList.remove('hidden');
  updateExportDeviceFilter();
  calculateExportCount();
}

// 关闭导出对话框
function closeExportModalFn() {
  exportModal.classList.add('hidden');
}

// ========== 关键词高亮设置对话框 ==========

// 渲染自定义关键词列表
function renderCustomKeywords() {
  const list = document.getElementById('customKeywordsList');
  list.innerHTML = '';
  
  highlightConfig.customKeywords.forEach((kw, index) => {
    const tag = document.createElement('div');
    tag.className = 'keyword-tag';
    tag.style.background = kw.color + '30';
    tag.innerHTML = `
      <span style="color: ${kw.color};">${escapeHtml(kw.word)}</span>
      <button onclick="removeCustomKeyword(${index})">✖</button>
    `;
    list.appendChild(tag);
  });
}

// 删除自定义关键词
window.removeCustomKeyword = function(index) {
  highlightConfig.customKeywords.splice(index, 1);
  renderCustomKeywords();
};

// 保存高亮设置
function saveHighlightSettings() {
  highlightConfig.enabled = document.getElementById('enableHighlight').checked;
  highlightConfig.rules.error.enabled = document.getElementById('highlightError').checked;
  highlightConfig.rules.warn.enabled = document.getElementById('highlightWarn').checked;
  highlightConfig.rules.crash.enabled = document.getElementById('highlightCrash').checked;
  highlightConfig.rules.network.enabled = document.getElementById('highlightNetwork').checked;
  highlightConfig.rules.performance.enabled = document.getElementById('highlightPerformance').checked;
  
  saveHighlightConfig();
  renderLogs();
  showToast('高亮设置已保存', 'success');
  closeHighlightModalFn();
}

// 打开高亮设置对话框
function openHighlightModalFn() {
  highlightModal.classList.remove('hidden');
  
  // 同步当前设置到UI
  document.getElementById('highlightError').checked = highlightConfig.rules.error.enabled;
  document.getElementById('highlightWarn').checked = highlightConfig.rules.warn.enabled;
  document.getElementById('highlightCrash').checked = highlightConfig.rules.crash.enabled;
  document.getElementById('highlightNetwork').checked = highlightConfig.rules.network.enabled;
  document.getElementById('highlightPerformance').checked = highlightConfig.rules.performance.enabled;
  
  renderCustomKeywords();
}

// 关闭高亮设置对话框
function closeHighlightModalFn() {
  highlightModal.classList.add('hidden');
}

// ========== 时间线视图 ==========

let timelineChart = null;
let perfUpdateInterval = null;

function renderTimeline() {
  const range = parseInt(document.getElementById('timelineRange').value);
  const interval = parseInt(document.getElementById('timelineInterval').value);
  const chartType = document.getElementById('timelineChartType').value;
  const canvas = document.getElementById('timelineCanvas');
  const canvasParent = document.getElementById('timelineChart');
  
  const MAX_GROUPS = 200;
  const groups = new Map();
  let totalLogs = 0, errorCount = 0, warnCount = 0;
  let firstTime = Infinity, lastTime = -Infinity;
  const cutoffTime = range > 0 ? Date.now() - (range * 1000) : 0;

  for (let i = 0; i < logs.length; i++) {
    const log = logs[i];
    const ts = log.timestamp || log.serverTime || 0;
    if (range > 0 && ts < cutoffTime) continue;
    
    totalLogs++;
    if (ts < firstTime) firstTime = ts;
    if (ts > lastTime) lastTime = ts;
    
    const groupTime = Math.floor(ts / (interval * 1000)) * (interval * 1000);
    let group = groups.get(groupTime);
    if (!group) {
      group = { total: 0, error: 0, warn: 0, info: 0, network: 0, playback: 0, debug: 0, crash: 0 };
      groups.set(groupTime, group);
    }
    const type = log.logType || log.type || 'info';
    group.total++;
    if (type === 'error') { group.error++; errorCount++; }
    else if (type === 'crash') { group.crash++; errorCount++; }
    else if (type === 'warn') { group.warn++; warnCount++; }
    else if (type === 'network') group.network++;
    else if (type === 'playback') group.playback++;
    else if (type === 'debug') group.debug++;
    else group.info++;
  }

  const timelTotal = document.getElementById('timelTotal');
  if (timelTotal) timelTotal.textContent = totalLogs.toLocaleString();
  const timelErrors = document.getElementById('timelErrors');
  if (timelErrors) { timelErrors.textContent = errorCount; timelErrors.style.color = errorCount > 0 ? '#f56565' : ''; }
  const timelWarns = document.getElementById('timelWarns');
  if (timelWarns) { timelWarns.textContent = warnCount; timelWarns.style.color = warnCount > 0 ? '#ed8936' : ''; }
  const timelGroups = document.getElementById('timelGroups');
  if (timelGroups) timelGroups.textContent = groups.size;
  const timelDuration = document.getElementById('timelDuration');
  if (timelDuration) {
    const duration = totalLogs > 0 ? (lastTime - firstTime) / 1000 : 0;
    timelDuration.textContent = duration < 60 ? Math.round(duration) + 's' : duration < 3600 ? (duration / 60).toFixed(1) + 'm' : (duration / 3600).toFixed(1) + 'h';
  }
  const timelPeak = document.getElementById('timelPeak');
  let maxCount = 0;
  for (const [, v] of groups) { if (v.total > maxCount) maxCount = v.total; }
  if (timelPeak) timelPeak.textContent = maxCount;

  if (totalLogs === 0) {
    if (canvas) {
      const ctx = canvas.getContext('2d');
      canvas.width = canvasParent.clientWidth;
      canvas.height = 320;
      ctx.fillStyle = '#718096';
      ctx.font = '14px sans-serif';
      ctx.textAlign = 'center';
      ctx.fillText('暂无日志数据', canvas.width / 2, canvas.height / 2);
    }
    return;
  }

  const allSortedGroups = [...groups.entries()].sort((a, b) => a[0] - b[0]);
  const sortedGroups = allSortedGroups.length > MAX_GROUPS
    ? allSortedGroups.slice(-MAX_GROUPS)
    : allSortedGroups;

  if (chartType === 'bars') {
    renderBarsChart(canvas, canvasParent, sortedGroups, firstTime, lastTime);
  } else if (chartType === 'area') {
    renderAreaChart(canvas, canvasParent, sortedGroups, firstTime, lastTime);
  } else if (chartType === 'heatmap') {
    renderHeatmapChart(canvas, canvasParent, sortedGroups, firstTime, lastTime);
  } else if (chartType === 'errorline') {
    renderErrorLineChart(canvas, canvasParent, sortedGroups, firstTime, lastTime);
  } else if (chartType === 'donut') {
    renderDonutChart(canvas, canvasParent, sortedGroups);
  }
}

function setupTimelineChartEvents() {
  const sel = document.getElementById('timelineChartType');
  if (sel) {
    sel.addEventListener('change', () => {
      renderTimeline();
    });
  }
}

// ========== Canvas 统计图渲染 ==========

const CHART_COLORS = {
  info: '#4299e1',
  network: '#63b3ed',
  playback: '#48bb78',
  warn: '#ed8936',
  error: '#f56565',
  crash: '#e53e3e',
  debug: '#a0aec0'
};

function setupCanvas(canvas, parent) {
  const dpr = window.devicePixelRatio || 1;
  const rect = parent.getBoundingClientRect();
  const w = Math.max(rect.width - 30, 300);
  const h = 640;
  canvas.style.height = h + 'px';
  canvas.width = w * dpr;
  canvas.height = h * dpr;
  const ctx = canvas.getContext('2d');
  ctx.scale(dpr, dpr);
  ctx.clearRect(0, 0, w, h);
  return { ctx, w, h };
}

function drawLegend(ctx, items, x, y) {
  ctx.font = '12px sans-serif';
  ctx.textAlign = 'left';
  let cx = x;
  for (const item of items) {
    ctx.fillStyle = item.color;
    ctx.fillRect(cx, y, 12, 12);
    ctx.fillStyle = '#a0aec0';
    ctx.fillText(item.label, cx + 16, y + 10);
    cx += ctx.measureText(item.label).width + 30;
  }
}

// 柱状图（原始）
function renderBarsChart(canvas, parent, groups) {
  const { ctx, w, h } = setupCanvas(canvas, parent);
  if (groups.length === 0) return;

  const padL = 50, padR = 20, padT = 25, padB = 90;
  const chartW = w - padL - padR;
  const chartH = h - padT - padB;
  const maxVal = Math.max(...groups.map(([, g]) => g.total));
  const barW = chartW / groups.length;
  const gap = Math.min(3, barW * 0.15);

  for (let i = 0; i <= 5; i++) {
    const y = padT + (chartH * i / 5);
    ctx.strokeStyle = 'rgba(255,255,255,0.08)';
    ctx.beginPath(); ctx.moveTo(padL, y); ctx.lineTo(w - padR, y); ctx.stroke();
    ctx.fillStyle = '#718096';
    ctx.font = '10px sans-serif';
    ctx.textAlign = 'right';
    ctx.fillText(Math.round(maxVal * (1 - i / 5)), padL - 5, y + 3);
  }

  for (let i = 0; i < groups.length; i++) {
    const [time, g] = groups[i];
    const x = padL + i * barW + gap / 2;
    const bw = barW - gap;
    let y = padT + chartH;
    const totalH = g.total > 0 ? chartH : 2;
    const parts = [
      ['info', g.info], ['debug', g.debug], ['playback', g.playback],
      ['network', g.network], ['warn', g.warn], ['error', g.error], ['crash', g.crash]
    ];
    for (const [key, val] of parts) {
      if (val === 0) continue;
      const hh = (val / Math.max(g.total, 1)) * totalH;
      y -= hh;
      ctx.fillStyle = CHART_COLORS[key];
      ctx.fillRect(x, y, bw, hh);
    }
    if (i % Math.ceil(groups.length / 10) === 0) {
      ctx.fillStyle = '#718096';
      ctx.font = '10px sans-serif';
      ctx.textAlign = 'center';
      const d = new Date(time);
      ctx.fillText(`${String(d.getHours()).padStart(2,'0')}:${String(d.getMinutes()).padStart(2,'0')}:${String(d.getSeconds()).padStart(2,'0')}`, x + bw / 2, padT + chartH + 25);
    }
    if (g.total > 0 && groups.length <= 30) {
      ctx.fillStyle = '#a0aec0';
      ctx.font = '10px sans-serif';
      ctx.textAlign = 'center';
      ctx.fillText(g.total, x + bw / 2, padT - 5);
    }
  }

  drawLegend(ctx, [
    { label: '信息', color: CHART_COLORS.info },
    { label: '网络', color: CHART_COLORS.network },
    { label: '播放', color: CHART_COLORS.playback },
    { label: '警告', color: CHART_COLORS.warn },
    { label: '错误', color: CHART_COLORS.error },
    { label: '崩溃', color: CHART_COLORS.crash }
  ], padL + 10, h - 15);
}

// 堆叠面积图
function renderAreaChart(canvas, parent, groups) {
  const { ctx, w, h } = setupCanvas(canvas, parent);
  if (groups.length === 0) return;

  const padL = 50, padR = 20, padT = 25, padB = 90;
  const chartW = w - padL - padR;
  const chartH = h - padT - padB;

  const series = ['info', 'debug', 'playback', 'network', 'warn', 'error', 'crash'];
  const seriesColors = series.map(s => CHART_COLORS[s]);
  const data = series.map(() => []);
  const totals = [];
  let maxTotal = 0;

  for (let i = 0; i < groups.length; i++) {
    const [, g] = groups[i];
    let stack = 0;
    const stackVals = [];
    for (let s = 0; s < series.length; s++) {
      stack += g[series[s]];
      stackVals.push(stack);
    }
    for (let s = 0; s < series.length; s++) {
      data[s].push(stackVals[s]);
    }
    totals.push(stack);
    if (stack > maxTotal) maxTotal = stack;
  }
  maxTotal = Math.max(maxTotal, 10);

  for (let i = 0; i <= 5; i++) {
    const y = padT + (chartH * i / 5);
    ctx.strokeStyle = 'rgba(255,255,255,0.08)';
    ctx.beginPath(); ctx.moveTo(padL, y); ctx.lineTo(w - padR, y); ctx.stroke();
    ctx.fillStyle = '#718096';
    ctx.font = '10px sans-serif';
    ctx.textAlign = 'right';
    ctx.fillText(Math.round(maxTotal * (1 - i / 5)), padL - 5, y + 3);
  }

  const xStep = chartW / Math.max(groups.length - 1, 1);
  const xOf = i => padL + i * xStep;
  const yOf = v => padT + chartH - (v / maxTotal) * chartH;

  for (let s = 0; s < series.length; s++) {
    ctx.beginPath();
    ctx.moveTo(xOf(0), padT + chartH);
    for (let i = 0; i < data[s].length; i++) {
      ctx.lineTo(xOf(i), yOf(data[s][i]));
    }
    ctx.lineTo(xOf(data[s].length - 1), padT + chartH);
    ctx.closePath();
    ctx.fillStyle = seriesColors[s] + '44';
    ctx.fill();
  }

  for (let s = 0; s < series.length; s++) {
    ctx.beginPath();
    for (let i = 0; i < data[s].length; i++) {
      const x = xOf(i), y = yOf(data[s][i]);
      if (i === 0) ctx.moveTo(x, y);
      else ctx.lineTo(x, y);
    }
    ctx.strokeStyle = seriesColors[s];
    ctx.lineWidth = 2;
    ctx.stroke();
  }

  for (let i = 0; i < groups.length; i++) {
    if (i % Math.ceil(groups.length / 10) === 0) {
      const [time] = groups[i];
      const d = new Date(time);
      ctx.fillStyle = '#718096';
      ctx.font = '10px sans-serif';
      ctx.textAlign = 'center';
      ctx.fillText(`${String(d.getHours()).padStart(2,'0')}:${String(d.getMinutes()).padStart(2,'0')}:${String(d.getSeconds()).padStart(2,'0')}`, xOf(i), padT + chartH + 25);
    }
  }

  drawLegend(ctx, [
    { label: '崩溃', color: CHART_COLORS.crash },
    { label: '错误', color: CHART_COLORS.error },
    { label: '警告', color: CHART_COLORS.warn },
    { label: '网络', color: CHART_COLORS.network },
    { label: '播放', color: CHART_COLORS.playback },
    { label: '调试', color: CHART_COLORS.debug },
    { label: '信息', color: CHART_COLORS.info }
  ], padL + 10, h - 15);
}

// 热力图
function renderHeatmapChart(canvas, parent, groups) {
  const { ctx, w, h } = setupCanvas(canvas, parent);
  if (groups.length === 0) return;

  const padL = 80, padR = 20, padT = 20, padB = 90;
  const chartW = w - padL - padR;
  const chartH = h - padT - padB;
  const types = ['crash', 'error', 'warn', 'network', 'playback', 'debug', 'info'];
  const typeLabels = ['崩溃', '错误', '警告', '网络', '播放', '调试', '信息'];
  const rows = types.length;
  const cols = Math.min(groups.length, 60);
  const cellW = chartW / cols;
  const cellH = chartH / rows;

  const sampledGroups = groups.length > cols
    ? groups.filter((_, i) => Math.floor(i / (groups.length / cols)) === Math.floor(cols / 2))
    : groups;

  const displayGroups = sampledGroups.length > cols
    ? sampledGroups.slice(-cols)
    : sampledGroups;

  let maxVal = 0;
  for (const [, g] of displayGroups) {
    for (const t of types) {
      if (g[t] > maxVal) maxVal = g[t];
    }
  }
  maxVal = Math.max(maxVal, 1);

  for (let r = 0; r < rows; r++) {
    ctx.fillStyle = '#a0aec0';
    ctx.font = '11px sans-serif';
    ctx.textAlign = 'right';
    ctx.textBaseline = 'middle';
    ctx.fillText(typeLabels[r], padL - 8, padT + r * cellH + cellH / 2);
  }

  for (let c = 0; c < displayGroups.length && c < cols; c++) {
    const [, g] = displayGroups[c];
    for (let r = 0; r < rows; r++) {
      const val = g[types[r]];
      const intensity = val / maxVal;
      const x = padL + c * cellW;
      const y = padT + r * cellH;
      if (intensity > 0) {
        ctx.fillStyle = CHART_COLORS[types[r]];
        ctx.globalAlpha = 0.15 + intensity * 0.85;
      } else {
        ctx.fillStyle = '#2d3748';
        ctx.globalAlpha = 0.3;
      }
      const cw = Math.max(cellW - 1, 2);
      const ch = Math.max(cellH - 1, 2);
      const rx = Math.round(x), ry = Math.round(y), rw = Math.round(cw), rh = Math.round(ch);
      if (rw > 0 && rh > 0) {
        ctx.fillRect(rx, ry, rw, rh);
      }
      ctx.globalAlpha = 1;
    }
  }

  const step = Math.max(1, Math.floor(displayGroups.length / 8));
  for (let c = 0; c < displayGroups.length; c += step) {
    const [time] = displayGroups[c];
    const d = new Date(time);
    ctx.fillStyle = '#718096';
    ctx.font = '10px sans-serif';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'top';
    ctx.fillText(`${String(d.getHours()).padStart(2,'0')}:${String(d.getMinutes()).padStart(2,'0')}`, padL + c * cellW + cellW / 2, padT + rows * cellH + 15);
  }

  ctx.fillStyle = '#718096';
  ctx.font = '10px sans-serif';
  ctx.textAlign = 'left';
  ctx.textBaseline = 'top';
  ctx.fillText('少', padL + chartW + 5, padT + 5);
  const grad = ctx.createLinearGradient(padL + chartW + 15, padT + 5, padL + chartW + 15, padT + 30);
  grad.addColorStop(0, 'rgba(66,153,225,0.15)');
  grad.addColorStop(1, 'rgba(66,153,225,1)');
  ctx.fillStyle = grad;
  ctx.fillRect(padL + chartW + 15, padT + 5, 10, 25);
  ctx.fillStyle = '#718096';
  ctx.fillText('多', padL + chartW + 28, padT + 30);
}

// 错误率折线图
function renderErrorLineChart(canvas, parent, groups) {
  const { ctx, w, h } = setupCanvas(canvas, parent);
  if (groups.length === 0) return;

  const padL = 50, padR = 20, padT = 25, padB = 90;
  const chartW = w - padL - padR;
  const chartH = h - padT - padB;

  const data = [];
  for (const [time, g] of groups) {
    const total = g.total || 1;
    const errorRate = ((g.error + g.crash) / total) * 100;
    const warnRate = (g.warn / total) * 100;
    data.push({ time, errorRate, warnRate, total: g.total });
  }

  const maxRate = Math.max(...data.map(d => Math.max(d.errorRate, d.warnRate)), 5);
  const threshold = 5;

  for (let i = 0; i <= 5; i++) {
    const y = padT + (chartH * i / 5);
    ctx.strokeStyle = 'rgba(255,255,255,0.08)';
    ctx.beginPath(); ctx.moveTo(padL, y); ctx.lineTo(w - padR, y); ctx.stroke();
    ctx.fillStyle = '#718096';
    ctx.font = '10px sans-serif';
    ctx.textAlign = 'right';
    ctx.fillText((maxRate * (1 - i / 5)).toFixed(1) + '%', padL - 5, y + 3);
  }

  const threshY = padT + chartH - (threshold / maxRate) * chartH;
  if (threshY > padT && threshY < padT + chartH) {
    ctx.strokeStyle = '#ed8936';
    ctx.lineWidth = 1;
    ctx.setLineDash([6, 4]);
    ctx.beginPath(); ctx.moveTo(padL, threshY); ctx.lineTo(w - padR, threshY); ctx.stroke();
    ctx.setLineDash([]);
    ctx.fillStyle = '#ed8936';
    ctx.font = '10px sans-serif';
    ctx.textAlign = 'left';
    ctx.fillText(`告警阈值 ${threshold}%`, padL + 5, threshY - 5);
  }

  const xStep = chartW / Math.max(data.length - 1, 1);
  const xOf = i => padL + i * xStep;
  const yOf = v => padT + chartH - (v / maxRate) * chartH;

  const errPath = new Path2D();
  const warnPath = new Path2D();
  errPath.moveTo(xOf(0), yOf(data[0].errorRate));
  warnPath.moveTo(xOf(0), yOf(data[0].warnRate));

  for (let i = 1; i < data.length; i++) {
    errPath.lineTo(xOf(i), yOf(data[i].errorRate));
    warnPath.lineTo(xOf(i), yOf(data[i].warnRate));
  }

  ctx.strokeStyle = '#ed8936';
  ctx.lineWidth = 2;
  ctx.stroke(warnPath);

  ctx.strokeStyle = '#f56565';
  ctx.lineWidth = 2.5;
  ctx.stroke(errPath);

  for (let i = 0; i < data.length; i++) {
    if (data[i].errorRate > threshold) {
      ctx.fillStyle = '#f56565';
      ctx.beginPath();
      ctx.arc(xOf(i), yOf(data[i].errorRate), 3, 0, Math.PI * 2);
      ctx.fill();
    }
  }

  const fillErrPath = new Path2D();
  fillErrPath.moveTo(xOf(0), padT + chartH);
  for (let i = 0; i < data.length; i++) {
    fillErrPath.lineTo(xOf(i), yOf(data[i].errorRate));
  }
  fillErrPath.lineTo(xOf(data.length - 1), padT + chartH);
  fillErrPath.closePath();
  ctx.fillStyle = 'rgba(245,101,101,0.15)';
  ctx.fill(fillErrPath);

  for (let i = 0; i < data.length; i++) {
    if (i % Math.ceil(data.length / 10) === 0) {
      const d = new Date(data[i].time);
      ctx.fillStyle = '#718096';
      ctx.font = '10px sans-serif';
      ctx.textAlign = 'center';
      ctx.fillText(`${String(d.getHours()).padStart(2,'0')}:${String(d.getMinutes()).padStart(2,'0')}:${String(d.getSeconds()).padStart(2,'0')}`, xOf(i), padT + chartH + 25);
    }
  }

  drawLegend(ctx, [
    { label: '错误率', color: '#f56565' },
    { label: '警告率', color: '#ed8936' }
  ], padL + 10, h - 15);
}

// 环形分布图
function renderDonutChart(canvas, parent, groups) {
  const { ctx, w, h } = setupCanvas(canvas, parent);
  if (groups.length === 0) return;

  const totals = { info: 0, debug: 0, network: 0, playback: 0, warn: 0, error: 0, crash: 0 };
  for (const [, g] of groups) {
    for (const k of Object.keys(totals)) totals[k] += g[k];
  }

  const entries = Object.entries(totals).filter(([_, v]) => v > 0);
  const grandTotal = entries.reduce((s, [_, v]) => s + v, 0);
  if (grandTotal === 0) return;

  const cx = w * 0.35;
  const cy = h / 2 - 10;
  const r = Math.min(w * 0.3, h * 0.35);
  const innerR = r * 0.6;

  let startAngle = -Math.PI / 2;
  const sorted = [...entries].sort((a, b) => b[1] - a[1]);

  for (const [key, val] of sorted) {
    const slice = (val / grandTotal) * Math.PI * 2;
    const endAngle = startAngle + slice;
    ctx.beginPath();
    ctx.arc(cx, cy, r, startAngle, endAngle);
    ctx.arc(cx, cy, innerR, endAngle, startAngle, true);
    ctx.closePath();
    ctx.fillStyle = CHART_COLORS[key];
    ctx.fill();
    startAngle = endAngle;
  }

  ctx.fillStyle = '#e2e8f0';
  ctx.font = 'bold 28px sans-serif';
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  ctx.fillText(grandTotal.toLocaleString(), cx, cy - 5);
  ctx.fillStyle = '#a0aec0';
  ctx.font = '13px sans-serif';
  ctx.fillText('总日志', cx, cy + 20);

  let ly = cy - sorted.length * 18;
  ctx.textAlign = 'left';
  ctx.textBaseline = 'middle';
  for (const [key, val] of sorted) {
    const pct = ((val / grandTotal) * 100).toFixed(1);
    ctx.fillStyle = CHART_COLORS[key];
    ctx.fillRect(w * 0.65, ly - 5, 12, 12);
    ctx.fillStyle = '#e2e8f0';
    ctx.font = '13px sans-serif';
    ctx.fillText(key === 'crash' ? '崩溃' : key === 'error' ? '错误' : key === 'warn' ? '警告' : key === 'network' ? '网络' : key === 'playback' ? '播放' : key === 'debug' ? '调试' : '信息', w * 0.65 + 18, ly);
    ctx.fillStyle = '#a0aec0';
    ctx.textAlign = 'right';
    ctx.fillText(`${val} (${pct}%)`, w - 20, ly);
    ctx.textAlign = 'left';
    ly += 22;
  }
}

function updateTimelineSummary(sortedGroups, filtered) {
  const totalLogs = filtered.length;
  const errorLogs = filtered.filter(l => {
    const type = l.logType || l.type;
    return type === 'error' || type === 'crash';
  }).length;
  const warnLogs = filtered.filter(l => {
    const type = l.logType || l.type;
    return type === 'warn';
  }).length;
  
  const timelTotal = document.getElementById('timelTotal');
  if (timelTotal) timelTotal.textContent = totalLogs.toLocaleString();
  
  const timelErrors = document.getElementById('timelErrors');
  if (timelErrors) {
    timelErrors.textContent = errorLogs;
    timelErrors.style.color = errorLogs > 0 ? '#f56565' : '';
  }
  
  const timelWarns = document.getElementById('timelWarns');
  if (timelWarns) {
    timelWarns.textContent = warnLogs;
    timelWarns.style.color = warnLogs > 0 ? '#ed8936' : '';
  }
  
  const timelGroups = document.getElementById('timelGroups');
  if (timelGroups) timelGroups.textContent = sortedGroups.length;
  
  const timelDuration = document.getElementById('timelDuration');
  if (timelDuration && sortedGroups.length > 0) {
    const firstTime = sortedGroups[0][0];
    const lastTime = sortedGroups[sortedGroups.length - 1][0];
    const duration = (lastTime - firstTime) / 1000;
    if (duration < 60) {
      timelDuration.textContent = Math.round(duration) + 's';
    } else if (duration < 3600) {
      timelDuration.textContent = (duration / 60).toFixed(1) + 'm';
    } else {
      timelDuration.textContent = (duration / 3600).toFixed(1) + 'h';
    }
  }
  
  const timelPeak = document.getElementById('timelPeak');
  if (timelPeak && sortedGroups.length > 0) {
    const peak = Math.max(...sortedGroups.map(([, v]) => v.total));
    timelPeak.textContent = peak;
  }
}

// 显示时间线提示框
window.showTimelineTooltip = function(event, time, data) {
  const existingTooltip = document.getElementById('timelineTooltip');
  if (existingTooltip) existingTooltip.remove();
  
  const tooltip = document.createElement('div');
  tooltip.id = 'timelineTooltip';
  tooltip.className = 'timeline-tooltip';
  const date = new Date(time);
  tooltip.innerHTML = `
    <strong>${formatTime(time)}</strong><br>
    总数: ${data.total} | 错误: ${data.error} | 警告: ${data.warn}
  `;
  
  document.body.appendChild(tooltip);
  
  const x = event.clientX + 10;
  const y = event.clientY + 10;
  tooltip.style.left = x + 'px';
  tooltip.style.top = y + 'px';
};

// 显示分组详情
window.showTimelineDetail = function(time, data) {
  const detail = document.getElementById('timelineDetail');
  const content = document.getElementById('timelineDetailContent');
  if (!detail || !content) return;

  const interval = parseInt(document.getElementById('timelineInterval').value) * 1000;
  const groupStart = time;
  const groupEnd = time + interval;
  
  const groupLogs = [];
  for (let i = 0; i < logs.length; i++) {
    const l = logs[i];
    const ts = l.timestamp || l.serverTime || 0;
    if (ts >= groupStart && ts < groupEnd) {
      groupLogs.push(l);
    }
  }

  const displayLogs = groupLogs.slice(-20).reverse();
  const totalCount = groupLogs.length;
  
  content.innerHTML = `
    <div style="margin-bottom: 12px; font-size: 13px; color: var(--text-secondary);">
      📅 ${formatDateTime(time)} | 共 ${totalCount} 条日志
    </div>
    ${displayLogs.map(log => {
      const type = log.logType || log.type || 'info';
      const message = escapeHtml(log.message || '');
      const typeClass = type === 'error' || type === 'crash' ? 'error' : type === 'warn' ? 'warn' : '';
      return `<div class="detail-log-item ${typeClass}">[${type.toUpperCase()}] ${message}</div>`;
    }).join('')}
    ${totalCount > 20 ? `<div style="text-align: center; color: var(--text-muted); font-size: 12px; margin-top: 10px;">... 还有 ${totalCount - 20} 条日志未显示</div>` : ''}
  `;
  
  detail.classList.remove('hidden');
}

// 隐藏时间线提示框
window.hideTimelineTooltip = function() {
  const tooltip = document.getElementById('timelineTooltip');
  if (tooltip) tooltip.remove();
};

// ========== 性能分析面板 ==========

let startupPerfCache = null;
let startupPerfCacheTime = 0;
const STARTUP_PERF_CACHE_TTL = 60000;

// 播放/网络性能：上一次采样（WIFI ADB 采集端）的流量 + 时间戳，用于算速率
let _lastTrafficSample = null; // { ts, rxBytes, txBytes }
let _lastPlaybackNetFetch = 0;
const PLAYBACK_NET_MIN_INTERVAL = 4000; // 节流：4s 内不重复向设备发 logcat 拉取

// 字节数 → 带单位可读文本（1024 进制）
function _fmtBytes(b) {
  if (b == null || !Number.isFinite(b)) return '--';
  if (b < 0) return '--';
  const TB = 1024 * 1024 * 1024 * 1024;
  const GB = 1024 * 1024 * 1024;
  const MB = 1024 * 1024;
  const KB = 1024;
  if (b >= TB) return (b / TB).toFixed(2) + ' TB';
  if (b >= GB) return (b / GB).toFixed(2) + ' GB';
  if (b >= MB) return (b / MB).toFixed(2) + ' MB';
  if (b >= KB) return (b / KB).toFixed(1) + ' KB';
  return Math.round(b) + ' B';
}
// 速率：输入「字节差值」和「毫秒间隔」→ 带单位的比特率
//   关键：即便差值为 0，也明确返回 "0.0 Kbps"，而不是用 "--" 让用户误以为「没数据」
function _fmtKbps(bytesDelta, msDelta) {
  if (!Number.isFinite(bytesDelta) || bytesDelta < 0) return '--';
  if (!Number.isFinite(msDelta) || msDelta <= 0) return '--';
  const kbps = (bytesDelta * 8) / (msDelta / 1000) / 1000;
  if (kbps >= 1000000) return (kbps / 1000000).toFixed(2) + ' Gbps';
  if (kbps >= 1000)    return (kbps / 1000).toFixed(2) + ' Mbps';
  return kbps.toFixed(1) + ' Kbps'; // 0 → "0.0 Kbps"，明确表示有数据但速率为 0
}
// 上一次「计算出来可用」的速率值；当 dt 太短或计数器没变时，
// 就显示旧值而不是跳回 "--"，视觉上保持连续
let _lastTrafficRates = { rxText: '0.0 Kbps', txText: '0.0 Kbps' };

// 获取用于操作 TV Live 的 ADB serial（启动性能测量 / 截图 / 录屏 等工具功能）
// 注意：必须返回 server 端 `/api/adb/devices` 返回的「原始 serial」（包括 WIFI 调试 GUID serial），
// 绝不能做任何字符替换（否则 adb -s <serial> 会报 device not found）。
// 规则：
//   1) 优先从性能卡/工具箱两个 select 的 value（已经由 updateAllDeviceSelects 填入真实 serial）
//   2) 其次从前端 devices Map 的 .adbSerial / .serial 字段（server 登记时保存的原始值）
//   3) 都空时，异步刷一次 /api/adb/devices 并自动选第一个；同步返回 null，调用方重试即可
// ❌ 绝不能用 adbDeviceId 反推 serial！因为 server 生成 id 时已用 replace(/[^a-zA-Z0-9]/g, '_') 丢失了原字符
let _adbSerialRefreshInited = false;
function getAdbSerial() {
  const direct = getPerformanceSerial();
  if (direct) return direct;

  for (const [, device] of devices) {
    if (device && device.adbSerial) return device.adbSerial;
    if (device && device.serial) return device.serial;
  }

  if (!_adbSerialRefreshInited) {
    _adbSerialRefreshInited = true;
    updateAllDeviceSelects().catch(() => {})
      .finally(() => { _adbSerialRefreshInited = false; });
  }
  return null;
}

async function measureStartupPerformance() {
  const serial = getAdbSerial();
  if (!serial) {
    showToast('未连接 ADB 设备，无法测量启动性能', 'error');
    return;
  }

  showToast('正在测量 TV Live 启动性能，请稍候...', 'info');
  document.getElementById('perfColdStart').textContent = '测量中...';
  document.getElementById('perfHotStart').textContent = '测量中...';
  document.getElementById('perfFirstFrame').textContent = '测量中...';
  document.getElementById('perfInteractive').textContent = '测量中...';

  try {
    const response = await fetch('/api/tvlive/startup-perf', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ serial })
    });
    const result = await response.json();
    
    if (result.success && result.data) {
      const d = result.data;
      const coldStartMs = d.coldStartMs || d.coldStart.totalTime || d.coldStart.waitTime;
      const hotStartMs = d.hotStartMs || d.hotStart.waitTime || d.hotStart.totalTime;
      const firstFrameMs = d.firstFrame;
      const interactiveMs = d.interactive;
      
      startupPerfCache = { cold: coldStartMs, hot: hotStartMs, frame: firstFrameMs, interactive: interactiveMs };
      startupPerfCacheTime = Date.now();
      
      document.getElementById('perfColdStart').textContent = coldStartMs ? coldStartMs + ' ms' : '-- ms';
      document.getElementById('perfHotStart').textContent = hotStartMs ? hotStartMs + ' ms' : '-- ms';
      document.getElementById('perfFirstFrame').textContent = firstFrameMs ? firstFrameMs + ' ms' : '-- ms';
      document.getElementById('perfInteractive').textContent = interactiveMs ? interactiveMs + ' ms' : '-- ms';
      
      showToast('启动性能测量完成', 'success');
    } else {
      throw new Error(result.error || '测量失败');
    }
  } catch (err) {
    showToast('启动性能测量失败: ' + err.message, 'error');
    document.getElementById('perfColdStart').textContent = '-- ms';
    document.getElementById('perfHotStart').textContent = '-- ms';
    document.getElementById('perfFirstFrame').textContent = '-- ms';
    document.getElementById('perfInteractive').textContent = '-- ms';
  }
}

function calculateStartupPerformance() {
  if (startupPerfCache && (Date.now() - startupPerfCacheTime) < STARTUP_PERF_CACHE_TTL) {
    document.getElementById('perfColdStart').textContent = startupPerfCache.cold + ' ms';
    document.getElementById('perfHotStart').textContent = startupPerfCache.hot + ' ms';
    document.getElementById('perfFirstFrame').textContent = startupPerfCache.frame + ' ms';
    document.getElementById('perfInteractive').textContent = startupPerfCache.interactive + ' ms';
    return;
  }
  
  const serial = getAdbSerial();
  if (!serial) {
    document.getElementById('perfColdStart').textContent = '请连接 ADB 设备';
    document.getElementById('perfHotStart').textContent = '请连接 ADB 设备';
    document.getElementById('perfFirstFrame').textContent = '请连接 ADB 设备';
    document.getElementById('perfInteractive').textContent = '请连接 ADB 设备';
    return;
  }
  
  document.getElementById('perfColdStart').textContent = '-- ms';
  document.getElementById('perfHotStart').textContent = '-- ms';
  document.getElementById('perfFirstFrame').textContent = '-- ms';
  document.getElementById('perfInteractive').textContent = '-- ms';
}

// 实时性能快照缓存（device-perf 返回后写入；renderPerformance 合并告警时读取）
// 所有字段都可能是 null/NaN，表示暂未采样到
let _lastPerfSnapshot = {
  cpuUsagePct: null,
  memUsagePct: null,
  memFreeMB:    null,
  fps:          null,
  batteryTempC: null,
  timestamp:    0
};

function renderPerformance() {
  const total = logs.length;
  let errorCount = 0, warnCount = 0;
  let playbackCount = 0, stallCount = 0, decodeErrCount = 0;
  let networkCount = 0, successRequestsCount = 0;
  const latencies = [];
  const stallTimes = [];
  const recentErrors = [];
  const latencyPattern = /(\d+)\s*ms/i;
  let firstTime = Infinity, lastTime = -Infinity;

  for (let i = 0; i < logs.length; i++) {
    const log = logs[i];
    const type = log.logType || log.type || 'info';
    const msg = log.message || '';
    const msgLower = msg.toLowerCase();
    const ts = log.timestamp || log.serverTime || 0;
    
    if (ts < firstTime) firstTime = ts;
    if (ts > lastTime) lastTime = ts;
    
    if (type === 'error' || type === 'crash') {
      errorCount++;
      recentErrors.push(log);
    } else if (type === 'warn') {
      warnCount++;
      recentErrors.push(log);
    }
    
    if (type === 'playback') {
      playbackCount++;
      if (msgLower.includes('stall') || msgLower.includes('卡顿')) {
        stallCount++;
        const m = msg.match(latencyPattern);
        if (m) stallTimes.push(parseInt(m[1]));
      }
      if (msgLower.includes('decode') || msgLower.includes('解码错误')) {
        decodeErrCount++;
      }
    }
    
    if (type === 'network') {
      networkCount++;
      if (msgLower.includes('success') || msgLower.includes('200') || msgLower.includes('完成')) {
        successRequestsCount++;
      }
      const m = msg.match(latencyPattern);
      if (m) latencies.push(parseInt(m[1]));
    }
  }

  const errorRate = total > 0 ? ((errorCount / total) * 100).toFixed(1) : '0';
  const warnRate = total > 0 ? ((warnCount / total) * 100).toFixed(1) : '0';

  document.getElementById('perfTotal').textContent = total;
  document.getElementById('perfErrorRate').textContent = errorRate + '%';
  document.getElementById('perfWarnRate').textContent = warnRate + '%';

  if (total >= 2 && lastTime > firstTime) {
    const durationMinutes = (lastTime - firstTime) / 60000;
    const rate = durationMinutes > 0 ? Math.round(total / durationMinutes) : 0;
    document.getElementById('perfRate').textContent = rate;
  } else {
    document.getElementById('perfRate').textContent = 0;
  }

  document.getElementById('perfPlayCount').textContent = playbackCount;
  document.getElementById('perfStallCount').textContent = stallCount;
  document.getElementById('perfDecodeErr').textContent = decodeErrCount;

  const avgStall = stallTimes.length > 0 ? Math.round(stallTimes.reduce((a, b) => a + b, 0) / stallTimes.length) : 0;
  const maxStall = stallTimes.length > 0 ? Math.max(...stallTimes) : 0;
  document.getElementById('perfAvgStall').textContent = avgStall + ' ms';
  if (document.getElementById('perfMaxStall')) {
    document.getElementById('perfMaxStall').textContent = maxStall + ' ms';
  }

  document.getElementById('perfRequests').textContent = networkCount;
  const successRate = networkCount > 0 ? ((successRequestsCount / networkCount) * 100).toFixed(1) : '0';
  document.getElementById('perfSuccessRate').textContent = successRate + '%';
  const avgLatency = latencies.length > 0 ? Math.round(latencies.reduce((a, b) => a + b, 0) / latencies.length) : 0;
  const maxLatency = latencies.length > 0 ? Math.max(...latencies) : 0;
  document.getElementById('perfAvgLatency').textContent = avgLatency + ' ms';
  document.getElementById('perfMaxLatency').textContent = maxLatency + ' ms';

  // 来源标签（会被下方异步 merge 覆盖，如果能抓到 ADB 数据）
  if (document.getElementById('perfPlaySource')) {
    const pc = playbackCount + stallCount + decodeErrCount;
    document.getElementById('perfPlaySource').textContent = pc > 0 ? '日志解析' : '日志解析（无播放事件）';
  }
  if (document.getElementById('perfNetSource')) {
    document.getElementById('perfNetSource').textContent = networkCount > 0 ? '日志解析' : '日志解析（无请求事件）';
  }

  // ===== 异步：从 WIFI ADB 直接抓播放/网络性能（logcat + TrafficStats），与日志解析结果 merge 展示 =====
  // 节流：4s 内不重复拉，避免对设备太频繁
  const nowT = Date.now();
  if (nowT - _lastPlaybackNetFetch >= PLAYBACK_NET_MIN_INTERVAL) {
    _lastPlaybackNetFetch = nowT;
    const serial = getAdbSerial();
    if (serial) {
      fetch('/api/tvlive/playback-net-perf', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ serial })
      }).then(r => r.json()).catch(() => null).then(result => {
        if (!result || !result.success || !result.data) return;
        const { playback: pb, network: nw } = result.data;
        if (!pb || !nw) return;

        // —— 播放性能 merge：取各指标 max（日志解析/ADB 解析双源，谁数据更全就用谁）
        const finalPlayCount  = Math.max(playbackCount, pb.playCount || 0);
        const finalStallCount = Math.max(stallCount, pb.stallCount || 0);
        const finalDecodeErr  = Math.max(decodeErrCount, pb.decodeErrCount || 0);
        // stallTimes 合并后重算平均/最大
        const allStalls = [...stallTimes, ...(pb.stallTimes || [])];
        const finalAvgStall = allStalls.length > 0 ? Math.round(allStalls.reduce((a, b) => a + b, 0) / allStalls.length) : 0;
        const finalMaxStall = allStalls.length > 0 ? Math.max(...allStalls) : 0;

        document.getElementById('perfPlayCount').textContent = finalPlayCount;
        document.getElementById('perfStallCount').textContent = finalStallCount;
        document.getElementById('perfDecodeErr').textContent = finalDecodeErr;
        document.getElementById('perfAvgStall').textContent = finalAvgStall + ' ms';
        if (document.getElementById('perfMaxStall')) {
          document.getElementById('perfMaxStall').textContent = finalMaxStall + ' ms';
        }

        // —— 网络性能 merge
        const finalReq  = Math.max(networkCount, nw.requestCount || 0);
        const finalSucc = Math.max(successRequestsCount, nw.successCount || 0);
        const finalErr  = Math.max((networkCount - successRequestsCount), nw.errorCount || 0);
        const finalDenom = finalSucc + finalErr;
        const finalSR = finalDenom > 0 ? ((finalSucc / finalDenom) * 100).toFixed(1) : (nw.successCount > 0 ? '100.0' : '0');
        const allLats = [...latencies, ...(nw.latencies || [])];
        const finalAvgLat = allLats.length > 0 ? Math.round(allLats.reduce((a, b) => a + b, 0) / allLats.length) : 0;
        const finalMaxLat = allLats.length > 0 ? Math.max(...allLats) : (nw.maxLatencyMs || maxLatency || 0);

        document.getElementById('perfRequests').textContent = finalReq;
        document.getElementById('perfSuccessRate').textContent = finalSR + '%';
        document.getElementById('perfAvgLatency').textContent = finalAvgLat + ' ms';
        document.getElementById('perfMaxLatency').textContent = finalMaxLat + ' ms';

        // 流量 + 速率
        if (document.getElementById('perfTrafficIO')) {
          document.getElementById('perfTrafficIO').textContent =
            `${_fmtBytes(nw.rxBytes)} / ${_fmtBytes(nw.txBytes)}`;
        }
        if (document.getElementById('perfTrafficRate')) {
          const ts = result.data.timestamp || nowT;
          // 1) 首次采样 / 没有上一帧 → 直接显示 0 Kbps（而不是 "--"）
          //    等下一次 4s 后有了差值，就会自动替换成真实速率
          if (!_lastTrafficSample) {
            if (nw.rxBytes != null || nw.txBytes != null) {
              _lastTrafficSample = { ts, rxBytes: nw.rxBytes, txBytes: nw.txBytes };
            }
            document.getElementById('perfTrafficRate').textContent =
              `↓ ${_lastTrafficRates.rxText}  ↑ ${_lastTrafficRates.txText}`;
          } else {
            const dt = ts - _lastTrafficSample.ts;
            // 2) 采样间隔太短（< 3s） → 保留上次速率，避免 dt 太小导致速率跳变不可信
            if (dt < 3000 || nw.rxBytes == null || nw.txBytes == null) {
              document.getElementById('perfTrafficRate').textContent =
                `↓ ${_lastTrafficRates.rxText}  ↑ ${_lastTrafficRates.txText}`;
              // 有新 rx/tx 就稍微更新一下 sample ts，但不要替换字节数（防止下一次 dt 不够）
              if (nw.rxBytes != null || nw.txBytes != null) {
                _lastTrafficSample = { ts, rxBytes: nw.rxBytes, txBytes: nw.txBytes };
              }
            } else {
              // 3) dt ≥ 3s：正式做差，算出新速率；即使 delta=0 也显示 0 Kbps
              const dr  = nw.rxBytes != null  && _lastTrafficSample.rxBytes != null
                ? Math.max(0, nw.rxBytes  - _lastTrafficSample.rxBytes)  : null;
              const dt2 = nw.txBytes != null && _lastTrafficSample.txBytes != null
                ? Math.max(0, nw.txBytes - _lastTrafficSample.txBytes) : null;
              const newRx = dr  != null ? _fmtKbps(dr,  dt) : _lastTrafficRates.rxText;
              const newTx = dt2 != null ? _fmtKbps(dt2, dt) : _lastTrafficRates.txText;
              // 速率不为 "--" 时才更新缓存值（保持连续性）
              if (newRx !== '--') _lastTrafficRates.rxText = newRx;
              if (newTx !== '--') _lastTrafficRates.txText = newTx;
              document.getElementById('perfTrafficRate').textContent =
                `↓ ${_lastTrafficRates.rxText}  ↑ ${_lastTrafficRates.txText}`;
              _lastTrafficSample = { ts, rxBytes: nw.rxBytes, txBytes: nw.txBytes };
            }
          }
        }

        // 告警：merge 后的最终值 + 最新实时性能快照 一起重算
        updatePerformanceAlerts({
          cpuUsagePct:   _lastPerfSnapshot.cpuUsagePct,
          memUsagePct:   _lastPerfSnapshot.memUsagePct,
          memFreeMB:     _lastPerfSnapshot.memFreeMB,
          fps:           _lastPerfSnapshot.fps,
          batteryTempC:  _lastPerfSnapshot.batteryTempC,
          stallCount:    finalStallCount,
          playCount:     finalPlayCount,
          errorRatePct:  parseFloat(errorRate) || 0,
          avgLatencyMs:  finalAvgLat,
          maxLatencyMs:  finalMaxLat
        });

        // 来源：标注双源（若 ADB 数据有缺失则附提示）
        const missing = result.data.missing || [];
        if (document.getElementById('perfPlaySource')) {
          const tags = [];
          if ((pb.playCount + pb.stallCount + pb.decodeErrCount) > 0) tags.push('ADB logcat');
          if ((playbackCount + stallCount + decodeErrCount) > 0) tags.push('日志解析');
          document.getElementById('perfPlaySource').textContent = tags.length
            ? tags.join(' + ') + (missing.includes('playback_logcat') ? '（设备播放日志无tag匹配）' : '')
            : '暂无播放数据';
        }
        if (document.getElementById('perfNetSource')) {
          const tags2 = [];
          if ((nw.requestCount + nw.successCount) > 0) tags2.push('ADB logcat');
          if ((networkCount + successRequestsCount) > 0) tags2.push('日志解析');
          if (nw.rxBytes != null || nw.txBytes != null) tags2.push('TrafficStats');
          document.getElementById('perfNetSource').textContent = tags2.length
            ? tags2.join(' + ')
            : '暂无网络数据';
        }
      }).catch(() => {});
    }
  }

  // 告警（兜底：若 playback-net-perf 还没返回/未执行，则用当前日志统计 + 最新实时性能快照 先算一次）
  updatePerformanceAlerts({
    cpuUsagePct:   _lastPerfSnapshot.cpuUsagePct,
    memUsagePct:   _lastPerfSnapshot.memUsagePct,
    memFreeMB:     _lastPerfSnapshot.memFreeMB,
    fps:           _lastPerfSnapshot.fps,
    batteryTempC:  _lastPerfSnapshot.batteryTempC,
    stallCount:    stallCount,
    playCount:     playbackCount,
    errorRatePct:  parseFloat(errorRate) || 0,
    avgLatencyMs:  avgLatency,
    maxLatencyMs:  maxLatency
  });

  calculateStartupPerformance();

  fetchDevicePerformance();

  const recentContainer = document.getElementById('perfRecentErrors');
  if (recentErrors.length === 0) {
    recentContainer.innerHTML = '<p class="empty-state">暂无错误与警告</p>';
  } else {
    const displayErrors = recentErrors.slice(-10).reverse();
    recentContainer.innerHTML = displayErrors.map(log => {
      const time = formatTime(log.timestamp || log.serverTime);
      const type = log.logType || log.type;
      const typeColor = type === 'crash' || type === 'error' ? 'var(--crash)' : 'var(--warning)';
      return `
        <div class="perf-error-item">
          <span class="perf-error-time">${time}</span>
          <span class="perf-error-msg" style="color: ${typeColor};">[${typeLabels[type] || type}] ${escapeHtml(log.message || '')}</span>
        </div>
      `;
    }).join('');
  }

  drawPerformanceChart();
}

// 更新性能告警
// 参数说明（全部可选，传 null/NaN 表示该指标暂时无数据，按"未知/无数据"显示警告）：
//   cpuUsagePct:  CPU 使用率 %（0~100）
//   memUsagePct:  内存占用率 %（0~100，= memUsed/memTotal*100，可留空按 memUsed 粗估）
//   memFreeMB:    剩余可用内存 MB（更直观的"内存不足"判断依据，优先于 memUsagePct）
//   fps:          实时帧率 FPS
//   batteryTempC: 电池温度 ℃
//   stallCount:   播放卡顿次数（来自日志解析+ADB合并后的次数）
//   playCount:    播放次数（播放事件命中次数，>0 表示确实有过播放行为）
//   errorRatePct: 日志错误率 %（= 错误条数/总条数*100，0~100），作为附加信号
//   avgLatencyMs: 网络平均请求延迟 ms
//   maxLatencyMs: 网络最大请求延迟 ms
function updatePerformanceAlerts({ cpuUsagePct=null, memUsagePct=null, memFreeMB=null, fps=null, batteryTempC=null,
                                    stallCount=0, playCount=0, errorRatePct=0, avgLatencyMs=0, maxLatencyMs=0 } = {}) {
  const setAlert = (id, text, level) => {
    const el = document.getElementById(id);
    if (!el) return;
    el.textContent = text;
    el.className = level === 'danger'  ? 'alert-danger'
                 : level === 'warning' ? 'alert-warning'
                 : level === 'nodata'  ? 'alert-warning'
                 :                       'alert-normal';
  };

  const has = v => Number.isFinite(v) && v !== null;

  // ------------------------------------------------------------
  // 1) CPU 过热：判断 CPU 使用率 + 电池温度，再附加 错误率/渲染卡顿
  // ------------------------------------------------------------
  const alertCpu = (() => {
    if (!has(cpuUsagePct) && !has(batteryTempC)) return { text: '无数据', level: 'nodata' };
    const cpuHi   = has(cpuUsagePct)   && cpuUsagePct   > 80;
    const cpuMid  = has(cpuUsagePct)   && cpuUsagePct   > 65;
    const battHi  = has(batteryTempC)  && batteryTempC  > 58;
    const battMid = has(batteryTempC)  && batteryTempC  > 48;
    const errHi   = has(errorRatePct)  && errorRatePct  > 10;
    if (cpuHi || battHi) return { text: 'CPU过热 / 高温', level: 'danger' };
    if (cpuMid || battMid || errHi) {
      const parts = [];
      if (cpuMid) parts.push('CPU偏高');
      if (battMid) parts.push('温度偏高');
      if (errHi) parts.push('错误率高');
      return { text: parts.join(' / ') || '负载偏高', level: 'warning' };
    }
    return { text: '正常', level: 'normal' };
  })();
  setAlert('alertCpuOverheat', alertCpu.text, alertCpu.level);

  // ------------------------------------------------------------
  // 2) 内存不足：优先剩余可用内存（MB），其次占用率
  // ------------------------------------------------------------
  const alertMem = (() => {
    if (!has(memFreeMB) && !has(memUsagePct)) return { text: '无数据', level: 'nodata' };
    // 剩余内存判断（更贴近"不足"这个语义）
    if (has(memFreeMB)) {
      if (memFreeMB < 200) return { text: '内存严重不足', level: 'danger' };
      if (memFreeMB < 500) return { text: '内存紧张',     level: 'warning' };
    }
    // 占用率判断作为补充
    if (has(memUsagePct)) {
      if (memUsagePct >= 90) return { text: '内存严重不足', level: 'danger' };
      if (memUsagePct >  75) return { text: '内存占用偏高', level: 'warning' };
    }
    // 另外：若当前进程占用（无总内存时只能粗估）> 1GB，提醒"占用偏高"
    return { text: '正常', level: 'normal' };
  })();
  setAlert('alertMemLow', alertMem.text, alertMem.level);

  // ------------------------------------------------------------
  // 3) 帧率过低：FPS 值为主，播放次数 + 卡顿次数作为门槛（避免未播放时 fps=0 被误判为停滞）
  //    判定前置：只有「playCount>0 或 stallCount>0」才认为 APP 正在渲染画面，
  //              否则即便是 fps<=0 也判「无播放/采样中」，而不是「画面停滞」
  // ------------------------------------------------------------
  const alertFps = (() => {
    const hasFps = has(fps);
    // 有播放证据（已命中播放事件），用于区分"未播放"和"真的卡住"
    const hasPlayEvidence = (stallCount > 0) || (playCount > 0);

    if (!hasFps && !hasPlayEvidence) return { text: '采样中', level: 'nodata' };

    // 频繁卡顿 → 无论 FPS 都直接打危险
    if (stallCount > 3) return { text: '频繁卡顿', level: 'danger' };

    if (hasFps) {
      if (fps <= 0) {
        // ⚠️ 关键：只有 APP 确实在播放（有播放证据）时 fps=0 才是真的「画面停滞」
        //        否则是 TVLive 在后台/没播内容，按「无播放」处理避免误报
        if (hasPlayEvidence) return { text: '画面停滞', level: 'danger' };
        return { text: '无播放画面', level: 'nodata' };
      }
      if (fps < 15)  return { text: '帧率过低', level: 'danger' };
      if (fps < 24)  return { text: '帧率偏低', level: 'warning' };
    }
    if (stallCount > 0) return { text: '偶发卡顿', level: 'warning' };
    return { text: '正常', level: 'normal' };
  })();
  setAlert('alertLowFps', alertFps.text, alertFps.level);

  // ------------------------------------------------------------
  // 4) 网络延迟高：avg / max 延迟 + 错误率辅助判断
  // ------------------------------------------------------------
  const alertNet = (() => {
    const avg = has(avgLatencyMs) ? avgLatencyMs : 0;
    const max = has(maxLatencyMs) ? maxLatencyMs : 0;
    if (avg <= 0 && max <= 0) return { text: '采样中', level: 'nodata' };
    if (avg > 3000 || max > 6000) return { text: '严重延迟', level: 'danger' };
    if (avg > 1000 || max > 3000) return { text: '延迟过高', level: 'warning' };
    return { text: '正常', level: 'normal' };
  })();
  setAlert('alertHighLatency', alertNet.text, alertNet.level);
}

// 获取设备实时性能
async function fetchDevicePerformance() {
  let serial = getPerformanceSerial();

  // 兜底：如果两个 select 都没选，立刻从 server 刷一次设备列表并自动选第一个
  if (!serial) {
    try { await updateAllDeviceSelects(); } catch (_) {}
    serial = getPerformanceSerial();
  }
  if (!serial) {
    updatePerfDisplay(null, '未连接 ADB 设备');
    return;
  }

  try {
    const response = await fetch('/api/tvlive/device-perf', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ serial })
    });
    const result = await response.json();
    if (result && result.success && result.data) {
      updatePerfDisplay(result.data, null);
    } else if (result && result.error) {
      updatePerfDisplay(null, `获取失败: ${String(result.error).slice(0, 24)}`);
    } else {
      updatePerfDisplay(null, '接口返回为空');
    }
  } catch (error) {
    console.error('获取性能数据失败:', error);
    updatePerfDisplay(null, `网络错误: ${error && error.message ? error.message.slice(0, 20) : '未知'}`);
  }
}

// 更新性能显示
function updatePerfDisplay(data, reason) {
  const $ = id => document.getElementById(id);
  const cpuUsage = $('perfCpuUsage'), cpuBar = $('perfCpuBar');
  const memUsage = $('perfMemUsage'), memBar = $('perfMemBar');
  const perfFps = $('perfFps'), batteryTemp = $('perfBatteryTemp');
  const perfLastUpdate = $('perfLastUpdate');

  const has = v => Number.isFinite(v);

  if (!data) {
    if (cpuUsage) cpuUsage.textContent = '--%';
    if (memUsage) memUsage.textContent = '-- MB';
    if (perfFps) perfFps.textContent = '--';
    if (batteryTemp) batteryTemp.textContent = '-- ℃';
    if (cpuBar) cpuBar.style.width = '0%';
    if (memBar) memBar.style.width = '0%';
    if (perfLastUpdate) perfLastUpdate.textContent = reason || '未连接';
    return;
  }

  // CPU
  const cpu = has(data.cpuUsage) ? Math.min(Math.max(0, data.cpuUsage), 100) : NaN;
  if (cpuUsage) cpuUsage.textContent = has(cpu) ? `${cpu.toFixed(1)}%` : '--%';
  if (cpuBar) {
    if (!has(cpu)) { cpuBar.style.width = '0%'; }
    else {
      cpuBar.style.width = cpu + '%';
      cpuBar.style.background = cpu > 80 ? 'linear-gradient(90deg, #f56565, #c53030)'
                             : cpu > 60 ? 'linear-gradient(90deg, #ed8936, #dd6b20)'
                                        : 'linear-gradient(90deg, #48bb78, #4299e1)';
    }
  }

  // 内存
  const memUsed = has(data.memUsed) ? data.memUsed : NaN;
  const memTotal = has(data.memTotal) ? data.memTotal : NaN;
  if (memUsage) {
    if (!has(memUsed)) memUsage.textContent = '-- MB';
    else if (has(memTotal) && memTotal > 0) {
      const usagePercent = Math.min(100, Math.max(0, (memUsed / memTotal) * 100));
      memUsage.textContent = `${memUsed.toFixed(0)} MB / ${memTotal.toFixed(0)} MB (${usagePercent.toFixed(1)}%)`;
      if (memBar) {
        memBar.style.width = usagePercent + '%';
        memBar.style.background = usagePercent > 80 ? 'linear-gradient(90deg, #f56565, #c53030)'
                                 : usagePercent > 60 ? 'linear-gradient(90deg, #ed8936, #dd6b20)'
                                                     : 'linear-gradient(90deg, #48bb78, #4299e1)';
      }
    } else {
      memUsage.textContent = `${memUsed.toFixed(0)} MB`;
      if (memBar) memBar.style.width = Math.min((memUsed / 2048) * 100, 100) + '%';
    }
  }

  // FPS（同时兼容后端返回 app_fps / gfxinfo_fps / surfaceflinger_fps 或最终 fps）
  const fpsVal = has(data.fps) ? data.fps : (has(data.gfxinfo_fps) ? data.gfxinfo_fps : NaN);
  if (perfFps) perfFps.textContent = has(fpsVal) ? `${fpsVal}` : '采样中…';

  // 电池温度
  const batt = has(data.batteryTemp) ? data.batteryTemp : NaN;
  if (batteryTemp) {
    batteryTemp.textContent = has(batt) ? `${batt.toFixed(1)} ℃` : '-- ℃';
  }

  // 写缓存：把实时性能快照存下来，供 renderPerformance / updatePerformanceAlerts 合并判断使用
  const memUsedV  = has(data.memUsed)  ? data.memUsed  : NaN;
  const memTotalV = has(data.memTotal) ? data.memTotal : NaN;
  let memPercent = NaN;
  if (has(memUsedV) && has(memTotalV) && memTotalV > 0) memPercent = (memUsedV / memTotalV) * 100;
  const memFreeV  = has(memTotalV) && has(memUsedV) ? Math.max(0, memTotalV - memUsedV) : NaN;
  const cpuV = has(data.cpuUsage) ? Math.min(100, Math.max(0, data.cpuUsage)) : NaN;

  _lastPerfSnapshot = {
    cpuUsagePct:  Number.isFinite(cpuV)   ? cpuV      : null,
    memUsagePct:  Number.isFinite(memPercent) ? memPercent : null,
    memFreeMB:    Number.isFinite(memFreeV)  ? memFreeV   : null,
    fps:          Number.isFinite(fpsVal)    ? fpsVal     : null,
    batteryTempC: Number.isFinite(batt)      ? batt       : null,
    timestamp:    data.timestamp || Date.now()
  };

  // 实时刷新告警（每 5 秒一次 device-perf 返回后，把最新 CPU/内存/帧率/电池温度 参与计算）
  try {
    updatePerformanceAlerts({
      cpuUsagePct:  _lastPerfSnapshot.cpuUsagePct,
      memUsagePct:  _lastPerfSnapshot.memUsagePct,
      memFreeMB:    _lastPerfSnapshot.memFreeMB,
      fps:          _lastPerfSnapshot.fps,
      batteryTempC: _lastPerfSnapshot.batteryTempC
    });
  } catch (_) {}

  // 更新时间 + 字段缺失 meta 提示
  if (perfLastUpdate) {
    const missing = (data.meta && data.meta.missingFields && data.meta.missingFields.length)
      ? ` | 缺:${data.meta.missingFields.join(',')}` : '';
    const extra = reason ? ` · ${reason}` : missing;
    perfLastUpdate.textContent = formatTime(data.timestamp || Date.now()) + extra;
  }

  // 启动定时更新（如果还没启动）
  if (!perfUpdateInterval) {
    perfUpdateInterval = setInterval(() => {
      const activeTab = document.querySelector('.tab.active');
      if (activeTab && activeTab.dataset.view === 'performance') {
        fetchDevicePerformance().catch(() => {});
      }
    }, 5000); // 每5秒更新
  }
}

// 绘制性能趋势图
function drawPerformanceChart() {
  const canvas = document.getElementById('perfCanvas');
  if (!canvas) return;
  
  // 设置正确的Canvas分辨率
  const rect = canvas.getBoundingClientRect();
  const dpr = window.devicePixelRatio || 1;
  if (canvas.width !== rect.width * dpr || canvas.height !== rect.height * dpr) {
    canvas.width = rect.width * dpr;
    canvas.height = rect.height * dpr;
  }
  
  const ctx = canvas.getContext('2d');
  ctx.scale(dpr, dpr);
  const width = rect.width;
  const height = rect.height;
  
  ctx.clearRect(0, 0, width, height);
  
  // 背景
  let bgColor = '#1a1f2e';
  try {
    const computed = getComputedStyle(document.body);
    const v = computed.getPropertyValue('--bg-primary').trim();
    if (v) bgColor = v;
  } catch(e) {}
  ctx.fillStyle = bgColor;
  ctx.fillRect(0, 0, width, height);
  
  // 如果没有足够数据，显示提示
  if (logs.length < 2) {
    ctx.fillStyle = '#718096';
    ctx.font = '14px sans-serif';
    ctx.textAlign = 'center';
    ctx.fillText('收集更多日志以显示趋势...', width / 2, height / 2);
    return;
  }
  
  const timeWindow = 60000;
  let startTime = Infinity, endTime = -Infinity;
  for (let i = 0; i < logs.length; i++) {
    const ts = logs[i].timestamp || logs[i].serverTime || Date.now();
    if (ts < startTime) startTime = ts;
    if (ts > endTime) endTime = ts;
  }
  if (startTime === Infinity) startTime = Date.now();
  if (endTime === -Infinity) endTime = Date.now();
  const duration = Math.max(endTime - startTime, 1000);
  const windowCount = Math.min(Math.ceil(duration / timeWindow), 30);
  
  const errorData = new Array(windowCount).fill(0);
  const warnData = new Array(windowCount).fill(0);
  const totalData = new Array(windowCount).fill(0);
  const networkData = new Array(windowCount).fill(0);
  
  logs.forEach(log => {
    const ts = log.timestamp || log.serverTime || startTime;
    const windowIndex = Math.min(Math.floor((ts - startTime) / timeWindow), windowCount - 1);
    const type = log.logType || log.type;
    totalData[windowIndex]++;
    if (type === 'error' || type === 'crash') errorData[windowIndex]++;
    if (type === 'warn') warnData[windowIndex]++;
    if (type === 'network') networkData[windowIndex]++;
  });
  
  // 绘制网格
  ctx.strokeStyle = 'rgba(255, 255, 255, 0.08)';
  ctx.lineWidth = 1;
  for (let i = 0; i <= 5; i++) {
    const y = (height - 40) * (i / 5) + 25;
    ctx.beginPath();
    ctx.moveTo(50, y);
    ctx.lineTo(width - 10, y);
    ctx.stroke();
  }
  
  // 计算最大值用于缩放
  const maxTotal = Math.max(...totalData, 1);
  const chartHeight = height - 60;
  const chartWidth = width - 70;
  
  // 绘制Y轴标签
  ctx.fillStyle = '#718096';
  ctx.font = '11px sans-serif';
  ctx.textAlign = 'right';
  ctx.textBaseline = 'middle';
  for (let i = 0; i <= 5; i++) {
    const y = chartHeight * (i / 5) + 25;
    const value = Math.round(maxTotal * (1 - i / 5));
    ctx.fillText(value.toString(), 45, y);
  }
  
  // 绘制时间轴标签
  ctx.textAlign = 'center';
  ctx.textBaseline = 'top';
  ctx.fillStyle = '#718096';
  for (let i = 0; i < windowCount; i++) {
    if (windowCount <= 10 || i % Math.ceil(windowCount / 10) === 0) {
      const x = 50 + (i / Math.max(windowCount - 1, 1)) * chartWidth;
      const t = new Date(startTime + i * timeWindow);
      const label = String(t.getHours()).padStart(2, '0') + ':' + String(t.getMinutes()).padStart(2, '0');
      ctx.fillText(label, x, height - 18);
    }
  }
  
  // 绘制网络线（青色）
  drawLine(ctx, networkData, chartWidth, chartHeight, maxTotal, '#63b3ed', 50, 25);
  
  // 绘制总日志线（蓝色）
  drawLine(ctx, totalData, chartWidth, chartHeight, maxTotal, '#4299e1', 50, 25);
  
  // 绘制警告线（橙色）
  drawLine(ctx, warnData, chartWidth, chartHeight, maxTotal, '#ed8936', 50, 25);
  
  // 绘制错误线（红色）
  drawLine(ctx, errorData, chartWidth, chartHeight, maxTotal, '#f56565', 50, 25);
  
  // 绘制图例
  ctx.font = '12px sans-serif';
  ctx.textAlign = 'left';
  ctx.textBaseline = 'middle';
  const legendY = height - 5;
  
  let lx = 60;
  const legendItems = [
    { color: '#4299e1', label: '总数' },
    { color: '#63b3ed', label: '网络' },
    { color: '#ed8936', label: '警告' },
    { color: '#f56565', label: '错误' }
  ];
  
  legendItems.forEach(item => {
    ctx.fillStyle = item.color;
    ctx.fillRect(lx, legendY - 6, 10, 10);
    ctx.fillStyle = '#a0aec0';
    ctx.fillText(item.label, lx + 14, legendY - 1);
    lx += ctx.measureText(item.label).width + 30;
  });
}

function drawLine(ctx, data, width, height, maxValue, color, offsetX, offsetY) {
  if (data.length < 2) return;
  
  const stepX = width / (data.length - 1);
  
  ctx.beginPath();
  ctx.strokeStyle = color;
  ctx.lineWidth = 2;
  
  data.forEach((value, index) => {
    const x = offsetX + index * stepX;
    const y = offsetY + height - (value / maxValue) * height;
    
    if (index === 0) {
      ctx.moveTo(x, y);
    } else {
      ctx.lineTo(x, y);
    }
  });
  
  ctx.stroke();
  
  // 填充区域
  ctx.lineTo(offsetX + (data.length - 1) * stepX, offsetY + height);
  ctx.lineTo(offsetX, offsetY + height);
  ctx.closePath();
  
  ctx.fillStyle = color + '20';
  ctx.fill();
}

// ========== 事件绑定 ==========

// 初始化加载配置
loadHighlightConfig();
document.getElementById('enableHighlight').checked = highlightConfig.enabled;

// 更新高亮状态
document.getElementById('enableHighlight').addEventListener('change', (e) => {
  highlightConfig.enabled = e.target.checked;
  saveHighlightConfig();
  renderLogs();
});

// 导出按钮
document.getElementById('exportLogsBtn').addEventListener('click', openExportModalFn);
document.getElementById('closeExportModal').addEventListener('click', closeExportModalFn);
document.getElementById('cancelExportBtn').addEventListener('click', closeExportModalFn);
document.getElementById('confirmExportBtn').addEventListener('click', executeExport);

// 导出筛选变更时更新预览
['exportType', 'exportDevice', 'exportTimeRange', 'exportMaxCount'].forEach(id => {
  document.getElementById(id).addEventListener('change', calculateExportCount);
  document.getElementById(id).addEventListener('input', calculateExportCount);
});

// 高亮设置按钮
document.getElementById('highlightSettingsBtn').addEventListener('click', openHighlightModalFn);
document.getElementById('closeHighlightModal').addEventListener('click', closeHighlightModalFn);
document.getElementById('cancelHighlightBtn').addEventListener('click', closeHighlightModalFn);
document.getElementById('saveHighlightBtn').addEventListener('click', saveHighlightSettings);

// 添加自定义关键词
document.getElementById('addKeyword').addEventListener('click', () => {
  const input = document.getElementById('newKeyword');
  const colorSelect = document.getElementById('keywordColor');
  const word = input.value.trim();
  
  if (!word) {
    showToast('请输入关键词', 'error');
    return;
  }
  
  if (highlightConfig.customKeywords.some(kw => kw.word === word)) {
    showToast('关键词已存在', 'error');
    return;
  }
  
  highlightConfig.customKeywords.push({ word, color: colorSelect.value });
  input.value = '';
  renderCustomKeywords();
  showToast(`已添加关键词: ${word}`, 'success');
});

// 时间线刷新
document.getElementById('refreshTimeline').addEventListener('click', renderTimeline);
document.getElementById('timelineRange').addEventListener('change', renderTimeline);
document.getElementById('timelineInterval').addEventListener('change', renderTimeline);
document.getElementById('timelineChartType').addEventListener('change', renderTimeline);

setupTimelineChartEvents();

// 启动性能测量
const measureBtn = document.getElementById('measureStartupBtn');
if (measureBtn) {
  measureBtn.addEventListener('click', () => {
    measureStartupPerformance();
  });
}

// 时间线导出
document.getElementById('exportTimeline').addEventListener('click', exportTimelineData);

// 导出时间线数据
function exportTimelineData() {
  const range = parseInt(document.getElementById('timelineRange').value);
  const interval = parseInt(document.getElementById('timelineInterval').value);
  
  let filtered = logs;
  if (range > 0) {
    const cutoffTime = Date.now() - (range * 1000);
    filtered = filtered.filter(l => {
      const ts = l.timestamp || l.serverTime || 0;
      return ts >= cutoffTime;
    });
  }
  
  // 按间隔分组
  const groups = new Map();
  filtered.forEach(log => {
    const ts = log.timestamp || log.serverTime || 0;
    const groupTime = Math.floor(ts / (interval * 1000)) * (interval * 1000);
    if (!groups.has(groupTime)) {
      groups.set(groupTime, { total: 0, error: 0, warn: 0, info: 0, network: 0, playback: 0 });
    }
    const group = groups.get(groupTime);
    const type = log.logType || log.type || 'info';
    group.total++;
    if (group[type] !== undefined) group[type]++;
  });
  
  const exportData = {
    exportTime: new Date().toISOString(),
    range: range === 0 ? 'all' : `${range}s`,
    interval: `${interval}s`,
    totalLogs: filtered.length,
    groups: [...groups.entries()].map(([time, data]) => ({
      time: new Date(time).toISOString(),
      timestamp: time,
      ...data
    }))
  };
  
  // 下载文件
  const blob = new Blob([JSON.stringify(exportData, null, 2)], { type: 'application/json' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `timeline_export_${new Date().getTime()}.json`;
  a.click();
  URL.revokeObjectURL(url);
  
  showToast('时间线数据已导出', 'success');
}

// ========== 工具函数 ==========

// 格式化时间
function formatTimeShort(timestamp) {
  if (!timestamp) return '--';
  const date = new Date(timestamp);
  const pad = (n) => String(n).padStart(2, '0');
  return pad(date.getHours()) + ':' + pad(date.getMinutes()) + ':' + pad(date.getSeconds()) + '.' + 
    String(date.getMilliseconds()).padStart(3, '0');
}

// HTML 转义
function escapeHtml(text) {
  const div = document.createElement('div');
  div.textContent = text || '';
  return div.innerHTML;
}

// Toast 提示
function showToast(message, type = 'info') {
  const toast = document.getElementById('toast');
  toast.textContent = message;
  toast.style.background = type === 'success' ? '#48bb78' : 
                           type === 'error' ? '#f56565' : 
                           type === 'warning' ? '#ed8936' : '#4299e1';
  toast.classList.add('show');
  setTimeout(() => toast.classList.remove('show'), 2000);
}
