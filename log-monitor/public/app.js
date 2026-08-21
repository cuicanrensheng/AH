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

const MAX_VISIBLE_LOGS = 2000;
const MAX_LOGS = 100000;
let pendingLogs = [];
let renderScheduled = false;
let lastRenderTime = 0;
const MIN_RENDER_INTERVAL = 16;

const logContainer = document.getElementById('logList');
const networkContainer = document.getElementById('networkList');
const playbackContainer = document.getElementById('playbackList');
const debugContainer = document.getElementById('debugList');
const crashContainer = document.getElementById('crashList');
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

  if (currentView === 'logs' || currentView === 'network' || currentView === 'playback' || currentView === 'debug' || currentView === 'crashes') {
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
    updateStats();
  } else {
    for (const log of batch) addLogImmediate(log);
    logCount.textContent = logs.length;
  }

  renderScheduled = false;
  if (pendingLogs.length > 0) {
    requestAnimationFrame(flushPendingLogs);
  }
}

function appendLogElements(newLogs) {
  const filtered = getFilteredLogs();
  const visibleFiltered = filtered.slice(-MAX_VISIBLE_LOGS);
  const reversed = [...visibleFiltered].reverse();
  const container = getCurrentContainer();
  if (!container) return;

  if (container.childElementCount > MAX_VISIBLE_LOGS) {
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
    default: return null;
  }
}

function renderLogs() {
  const filtered = getFilteredLogs();
  const visibleLogs = filtered.slice(-MAX_VISIBLE_LOGS);
  const reversed = [...visibleLogs].reverse();
  
  if (currentView === 'logs') {
    logContainer.innerHTML = '';
    const fragment = document.createDocumentFragment();
    reversed.forEach(log => fragment.appendChild(createLogElement(log)));
    logContainer.appendChild(fragment);
    if (autoScroll && logContainer.firstChild) logContainer.scrollTop = 0;
  } else if (currentView === 'network') {
    networkContainer.innerHTML = '';
    const fragment = document.createDocumentFragment();
    reversed.filter(l => (l.logType || l.type) === 'network').forEach(log => {
      fragment.appendChild(createLogElement(log));
    });
    networkContainer.appendChild(fragment);
    if (autoScroll && networkContainer.firstChild) networkContainer.scrollTop = 0;
  } else if (currentView === 'playback') {
    playbackContainer.innerHTML = '';
    const fragment = document.createDocumentFragment();
    reversed.filter(l => (l.logType || l.type) === 'playback').forEach(log => {
      fragment.appendChild(createLogElement(log));
    });
    playbackContainer.appendChild(fragment);
    if (autoScroll && playbackContainer.firstChild) playbackContainer.scrollTop = 0;
  } else if (currentView === 'debug') {
    debugContainer.innerHTML = '';
    const fragment = document.createDocumentFragment();
    reversed.filter(l => (l.logType || l.type) === 'debug').forEach(log => {
      fragment.appendChild(createLogElement(log));
    });
    debugContainer.appendChild(fragment);
    if (autoScroll && debugContainer.firstChild) debugContainer.scrollTop = 0;
  } else if (currentView === 'crashes') {
    crashContainer.innerHTML = '';
    const fragment = document.createDocumentFragment();
    reversed.filter(l => l.logType === 'crash' || l.type === 'crash').forEach(log => {
      const el = createLogElement(log);
      el.style.borderLeftColor = 'var(--crash)';
      fragment.appendChild(el);
    });
    crashContainer.appendChild(fragment);
    if (autoScroll && crashContainer.firstChild) crashContainer.scrollTop = 0;
  }
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
  let filtered = logs;
  // 过滤系统日志
  if (filterSystemLogs) {
    filtered = filtered.filter(l => !l.isSystemLog);
  }
  const typeValue = typeFilter.value;
  if (typeValue) filtered = filtered.filter(l => (l.logType || l.type) === typeValue);
  const deviceValue = deviceFilter.value;
  if (deviceValue) filtered = filtered.filter(l => l.deviceId === deviceValue);
  const searchValue = searchFilter.value.toLowerCase();
  if (searchValue) {
    filtered = filtered.filter(l => {
      const tag = (l.tag || '').toLowerCase();
      const message = (l.message || '').toLowerCase();
      return tag.includes(searchValue) || message.includes(searchValue);
    });
  }
  return filtered;
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
  return date.toLocaleTimeString('zh-CN', { hour12: false }) + '.' + String(date.getMilliseconds()).padStart(3, '0');
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
    const response = await fetch('/api/adb/devices');
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
                          device.state === 'offline' ? '离线' : '未授权';
        
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
      
      // 如果只有一台设备且可用，自动连接
      const availableDevices = result.devices.filter(d => d.state === 'device');
      if (availableDevices.length === 1) {
        showToast(`发现设备，正在自动连接...`, 'info');
        await connectAdbDevice(availableDevices[0].serial);
      } else {
        showToast(`发现 ${result.devices.length} 台 ADB 设备，点击设备连接`, 'success');
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

document.getElementById('exportLogsBtn').addEventListener('click', () => {
  const params = new URLSearchParams();
  const deviceId = deviceFilter.value;
  const type = typeFilter.value;
  if (deviceId) params.append('deviceId', deviceId);
  if (type) params.append('type', type);
  window.location.href = `/api/logs/export?${params.toString()}`;
});

document.getElementById('autoScroll').addEventListener('change', (e) => { autoScroll = e.target.checked; });
document.getElementById('pauseLogs').addEventListener('change', (e) => { pauseUpdates = e.target.checked; });
document.getElementById('autoClearOnDisconnect').addEventListener('change', (e) => { autoClearOnDisconnect = e.target.checked; });

document.querySelectorAll('.tab').forEach(tab => {
  tab.addEventListener('click', () => {
    document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
    tab.classList.add('active');
    currentView = tab.dataset.view;
    document.getElementById('logsView').classList.toggle('hidden', currentView !== 'logs');
    document.getElementById('networkView').classList.toggle('hidden', currentView !== 'network');
    document.getElementById('playbackView').classList.toggle('hidden', currentView !== 'playback');
    document.getElementById('debugView').classList.toggle('hidden', currentView !== 'debug');
    document.getElementById('crashesView').classList.toggle('hidden', currentView !== 'crashes');
    document.getElementById('statsView').classList.toggle('hidden', currentView !== 'stats');
    document.getElementById('currentView').textContent = 
      currentView === 'logs' ? '显示全部日志' :
      currentView === 'network' ? '仅显示网络日志' :
      currentView === 'playback' ? '仅显示播放日志' :
      currentView === 'debug' ? '仅显示调试日志' :
      currentView === 'crashes' ? '仅显示崩溃日志' :
      '显示统计信息';
    renderLogs();
    updateStats();
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

function updateToolboxDevices() {
  const sel = document.getElementById('toolboxDeviceSel');
  if (!sel) return;
  fetch('/api/adb/devices').then(r => r.json()).then(result => {
    if (!result.success) return;
    const devices = result.devices.filter(d => d.state === 'device');
    sel.innerHTML = '<option value="">请选择设备</option>';
    devices.forEach(d => {
      const opt = document.createElement('option');
      opt.value = d.serial;
      opt.textContent = `${d.serial}${d.isEmulator ? ' (模拟器)' : ''}`;
      sel.appendChild(opt);
    });
  }).catch(() => {});
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
    
    showToast('正在读取 APK 文件...', 'info');
    try {
      const apkData = await fileToBase64(file);
      showToast('正在安装 APK 到设备...', 'info');
      const res = await fetch('/api/adb/install', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ serial, apkPath: file.name, apkData })
      });
      const data = await res.json();
      showToolboxResult(data.success ? `<pre>${data.output || '安装成功'}</pre>` : `<span class="result-error">${data.error}</span>`, data.success ? 'success' : 'error');
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
      showToast('正在推送...', 'info');
      const serial = getToolboxSerial();
      if (!serial) return;
      const localPath = file.name;
      try {
        const res = await fetch('/api/adb/push', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ serial, localPath, remotePath: v.remotePath })
        });
        const data = await res.json();
        showToolboxResult(data.success ? `<pre>${data.output}</pre>` : `<span class="result-error">${data.error}</span>`, data.success ? 'success' : 'error');
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

function huyaPost(endpoint, body) {
  return fetch(endpoint, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body || {})
  }).then(r => r.json());
}

function huyaGet(endpoint) {
  return fetch(endpoint).then(r => r.json());
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

// 加载凭证信息
document.getElementById('btnHuyaCredToggle').addEventListener('click', () => {
  const body = document.getElementById('huyaCredBody');
  if (body.classList.contains('hidden')) {
    huyaGet('/api/huya/credentials').then(data => {
      if (data.success) {
        document.getElementById('huyaGameId').textContent = data.raw.gameId;
        document.getElementById('huyaAppId').textContent = data.raw.appId;
        document.getElementById('huyaAppKey').textContent = data.raw.appKey;
        body.classList.remove('hidden');
      }
    });
  } else {
    body.classList.add('hidden');
  }
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
          const time = new Date(entry.timestamp).toLocaleTimeString();
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
    document.getElementById('cloudLastSync').textContent = date.toLocaleTimeString();
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
    const now = new Date().toLocaleTimeString();
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
  content += `导出时间: ${new Date().toLocaleString('zh-CN')}\n`;
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
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}

// 执行导出
function executeExport() {
  const format = document.querySelector('input[name="exportFormat"]:checked').value;
  const { filtered, count } = calculateExportCount();
  const logsToExport = filtered.slice(0, count);
  
  const options = {
    includeMetadata: document.getElementById('exportIncludeMetadata').checked,
    includeTimestamp: document.getElementById('exportIncludeTimestamp').checked,
    sorted: document.getElementById('exportSorted').checked
  };
  
  const timestamp = new Date().toISOString().replace(/[:.]/g, '-');
  
  switch (format) {
    case 'json':
      const jsonContent = exportAsJSON(logsToExport, options);
      triggerDownload(jsonContent, `tv-live-logs-${timestamp}.json`, 'application/json');
      break;
    case 'csv':
      const csvContent = exportAsCSV(logsToExport, options);
      triggerDownload(csvContent, `tv-live-logs-${timestamp}.csv`, 'text/csv;charset=utf-8');
      break;
    case 'txt':
      const txtContent = exportAsTXT(logsToExport, options);
      triggerDownload(txtContent, `tv-live-logs-${timestamp}.txt`, 'text/plain;charset=utf-8');
      break;
  }
  
  showToast(`已导出 ${count} 条日志为 ${format.toUpperCase()} 格式`, 'success');
  closeExportModalFn();
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

function renderTimeline() {
  const range = parseInt(document.getElementById('timelineRange').value);
  const interval = parseInt(document.getElementById('timelineInterval').value);
  const chart = document.getElementById('timelineChart');
  
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
  
  // 渲染图表
  const sortedGroups = [...groups.entries()].sort((a, b) => a[0] - b[0]);
  
  if (sortedGroups.length === 0) {
    chart.innerHTML = '<p class="empty-state">暂无日志数据</p>';
    return;
  }
  
  const maxCount = Math.max(...sortedGroups.map(([, v]) => v.total));
  
  let html = '<div class="timeline-bars" style="position: relative;">';
  
  sortedGroups.forEach(([time, data], index) => {
    const heightPercent = (data.total / maxCount) * 100;
    const date = new Date(time);
    const timeLabel = date.toLocaleTimeString('zh-CN', { hour12: false });
    
    const hasError = data.error > 0 || data.total > 0 && sortedGroups[index][1].total > 0;
    let barColor = 'var(--accent)';
    if (data.error > 0 || sortedGroups[index][1].error > 0) {
      barColor = 'var(--error)';
    } else if (data.warn > 0) {
      barColor = 'var(--warning)';
    }
    
    html += `
      <div class="timeline-bar" style="height: ${Math.max(heightPercent, 5)}%; background: ${barColor};" 
           data-time="${time}" data-count="${data.total}"
           onmouseenter="showTimelineTooltip(event, ${time}, ${JSON.stringify(data).replace(/"/g, '&quot;')})"
           onmouseleave="hideTimelineTooltip()">
        <span class="bar-count">${data.total > 0 ? data.total : ''}</span>
      </div>
    `;
  });
  
  html += '</div>';
  html += '<div style="display: flex; justify-content: space-between; margin-top: 25px; font-size: 11px; color: var(--text-muted);">';
  
  if (sortedGroups.length > 0) {
    const firstTime = new Date(sortedGroups[0][0]);
    const lastTime = new Date(sortedGroups[sortedGroups.length - 1][0]);
    html += `<span>${firstTime.toLocaleTimeString('zh-CN', { hour12: false })}</span>`;
    html += `<span>${lastTime.toLocaleTimeString('zh-CN', { hour12: false })}</span>`;
  }
  
  html += '</div>';
  
  // 添加图例
  html += '<div style="display: flex; gap: 15px; margin-top: 15px; justify-content: center;">';
  html += '<div style="display: flex; align-items: center; gap: 5px;"><span style="width: 12px; height: 12px; background: var(--accent); border-radius: 2px;"></span><span style="font-size: 12px; color: var(--text-secondary);">普通</span></div>';
  html += '<div style="display: flex; align-items: center; gap: 5px;"><span style="width: 12px; height: 12px; background: var(--warning); border-radius: 2px;"></span><span style="font-size: 12px; color: var(--text-secondary);">警告</span></div>';
  html += '<div style="display: flex; align-items: center; gap: 5px;"><span style="width: 12px; height: 12px; background: var(--error); border-radius: 2px;"></span><span style="font-size: 12px; color: var(--text-secondary);">错误/崩溃</span></div>';
  html += '</div>';
  
  chart.innerHTML = html;
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
    <strong>${date.toLocaleTimeString('zh-CN', { hour12: false })}</strong><br>
    总数: ${data.total} | 错误: ${data.error} | 警告: ${data.warn}
  `;
  
  document.body.appendChild(tooltip);
  
  const x = event.clientX + 10;
  const y = event.clientY + 10;
  tooltip.style.left = x + 'px';
  tooltip.style.top = y + 'px';
};

// 隐藏时间线提示框
window.hideTimelineTooltip = function() {
  const tooltip = document.getElementById('timelineTooltip');
  if (tooltip) tooltip.remove();
};

// ========== 性能分析面板 ==========

function renderPerformance() {
  // 日志统计
  const total = logs.length;
  const errorCount = logs.filter(l => l.logType === 'error' || l.type === 'error' || l.logType === 'crash' || l.type === 'crash').length;
  const warnCount = logs.filter(l => l.logType === 'warn' || l.type === 'warn').length;
  const errorRate = total > 0 ? ((errorCount / total) * 100).toFixed(1) : '0';
  const warnRate = total > 0 ? ((warnCount / total) * 100).toFixed(1) : '0';
  
  document.getElementById('perfTotal').textContent = total;
  document.getElementById('perfErrorRate').textContent = errorRate + '%';
  document.getElementById('perfWarnRate').textContent = warnRate + '%';
  
  // 计算日志速率（每分钟）
  if (logs.length >= 2) {
    const firstLog = logs[0];
    const lastLog = logs[logs.length - 1];
    const firstTime = firstLog.timestamp || firstLog.serverTime || 0;
    const lastTime = lastLog.timestamp || lastLog.serverTime || 0;
    const durationMinutes = (lastTime - firstTime) / 60000;
    const rate = durationMinutes > 0 ? Math.round(total / durationMinutes) : 0;
    document.getElementById('perfRate').textContent = rate;
  } else {
    document.getElementById('perfRate').textContent = 0;
  }
  
  // 播放统计
  const playbackLogs = logs.filter(l => l.logType === 'playback' || l.type === 'playback');
  const stallLogs = playbackLogs.filter(l => (l.message || '').toLowerCase().includes('stall') || (l.message || '').toLowerCase().includes('卡顿'));
  const decodeErrors = playbackLogs.filter(l => (l.message || '').toLowerCase().includes('decode') || (l.message || '').toLowerCase().includes('解码错误'));
  
  document.getElementById('perfPlayCount').textContent = playbackLogs.length;
  document.getElementById('perfStallCount').textContent = stallLogs.length;
  document.getElementById('perfDecodeErr').textContent = decodeErrors.length;
  
  // 计算平均卡顿时间（如果日志中有记录）
  const stallTimes = [];
  stallLogs.forEach(log => {
    const msg = log.message || '';
    const match = msg.match(/(\d+)\s*ms/i);
    if (match) stallTimes.push(parseInt(match[1]));
  });
  const avgStall = stallTimes.length > 0 ? Math.round(stallTimes.reduce((a, b) => a + b, 0) / stallTimes.length) : 0;
  document.getElementById('perfAvgStall').textContent = avgStall + ' ms';
  
  // 网络统计
  const networkLogs = logs.filter(l => l.logType === 'network' || l.type === 'network');
  const successRequests = networkLogs.filter(l => {
    const msg = (l.message || '').toLowerCase();
    return msg.includes('success') || msg.includes('200') || msg.includes('完成');
  });
  const latencyPattern = /(\d+)\s*ms/i;
  const latencies = [];
  networkLogs.forEach(log => {
    const msg = log.message || '';
    const match = msg.match(latencyPattern);
    if (match) latencies.push(parseInt(match[1]));
  });
  
  document.getElementById('perfRequests').textContent = networkLogs.length;
  const successRate = networkLogs.length > 0 ? ((successRequests.length / networkLogs.length) * 100).toFixed(1) : '0';
  document.getElementById('perfSuccessRate').textContent = successRate + '%';
  const avgLatency = latencies.length > 0 ? Math.round(latencies.reduce((a, b) => a + b, 0) / latencies.length) : 0;
  const maxLatency = latencies.length > 0 ? Math.max(...latencies) : 0;
  document.getElementById('perfAvgLatency').textContent = avgLatency + ' ms';
  document.getElementById('perfMaxLatency').textContent = maxLatency + ' ms';
  
  // 显示最近错误
  const recentErrors = logs.filter(l => l.logType === 'error' || l.type === 'error' || l.logType === 'crash' || l.type === 'crash' || l.logType === 'warn' || l.type === 'warn');
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
  
  // 绘制性能趋势图
  drawPerformanceChart();
}

// 绘制性能趋势图
function drawPerformanceChart() {
  const canvas = document.getElementById('perfCanvas');
  if (!canvas) return;
  
  const ctx = canvas.getContext('2d');
  const width = canvas.width;
  const height = canvas.height;
  
  ctx.clearRect(0, 0, width, height);
  
  // 背景
  ctx.fillStyle = getComputedStyle(document.body).getPropertyValue('--bg-primary') || '#1a1f2e';
  ctx.fillRect(0, 0, width, height);
  
  // 如果没有足够数据，显示提示
  if (logs.length < 2) {
    ctx.fillStyle = getComputedStyle(document.body).getPropertyValue('--text-muted') || '#718096';
    ctx.font = '14px sans-serif';
    ctx.textAlign = 'center';
    ctx.fillText('收集更多日志以显示趋势...', width / 2, height / 2);
    return;
  }
  
  // 按时间分组统计错误数
  const timeWindow = 60000; // 1分钟窗口
  const startTime = Math.min(...logs.map(l => l.timestamp || l.serverTime || Date.now()));
  const endTime = Math.max(...logs.map(l => l.timestamp || l.serverTime || Date.now()));
  const duration = endTime - startTime;
  const windowCount = Math.min(Math.ceil(duration / timeWindow), 30);
  
  const errorData = new Array(windowCount).fill(0);
  const warnData = new Array(windowCount).fill(0);
  const totalData = new Array(windowCount).fill(0);
  
  logs.forEach(log => {
    const ts = log.timestamp || log.serverTime || startTime;
    const windowIndex = Math.min(Math.floor((ts - startTime) / timeWindow), windowCount - 1);
    const type = log.logType || log.type;
    totalData[windowIndex]++;
    if (type === 'error' || type === 'crash') errorData[windowIndex]++;
    if (type === 'warn') warnData[windowIndex]++;
  });
  
  // 绘制网格
  ctx.strokeStyle = 'rgba(255, 255, 255, 0.1)';
  ctx.lineWidth = 1;
  for (let i = 0; i <= 5; i++) {
    const y = (height - 30) * (i / 5) + 20;
    ctx.beginPath();
    ctx.moveTo(40, y);
    ctx.lineTo(width - 10, y);
    ctx.stroke();
  }
  
  // 计算最大值用于缩放
  const maxTotal = Math.max(...totalData, 1);
  const chartHeight = height - 50;
  const chartWidth = width - 60;
  
  // 绘制Y轴标签
  ctx.fillStyle = getComputedStyle(document.body).getPropertyValue('--text-muted') || '#718096';
  ctx.font = '10px sans-serif';
  ctx.textAlign = 'right';
  for (let i = 0; i <= 5; i++) {
    const y = chartHeight * (i / 5) + 20;
    const value = Math.round(maxTotal * (1 - i / 5));
    ctx.fillText(value.toString(), 35, y + 3);
  }
  
  // 绘制总日志线
  drawLine(ctx, totalData, chartWidth, chartHeight, maxTotal, '#4299e1', 40, 20);
  
  // 绘制警告线
  drawLine(ctx, warnData, chartWidth, chartHeight, maxTotal, '#ed8936', 40, 20);
  
  // 绘制错误线
  drawLine(ctx, errorData, chartWidth, chartHeight, maxTotal, '#f56565', 40, 20);
  
  // 绘制图例
  ctx.font = '11px sans-serif';
  ctx.textAlign = 'left';
  const legendY = height - 5;
  
  ctx.fillStyle = '#4299e1';
  ctx.fillRect(100, legendY - 8, 10, 10);
  ctx.fillStyle = getComputedStyle(document.body).getPropertyValue('--text-secondary') || '#a0aec0';
  ctx.fillText('总数', 115, legendY);
  
  ctx.fillStyle = '#ed8936';
  ctx.fillRect(170, legendY - 8, 10, 10);
  ctx.fillStyle = getComputedStyle(document.body).getPropertyValue('--text-secondary') || '#a0aec0';
  ctx.fillText('警告', 185, legendY);
  
  ctx.fillStyle = '#f56565';
  ctx.fillRect(240, legendY - 8, 10, 10);
  ctx.fillStyle = getComputedStyle(document.body).getPropertyValue('--text-secondary') || '#a0aec0';
  ctx.fillText('错误', 255, legendY);
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

// 标签页切换更新
const originalTabClickHandler = document.querySelectorAll('.tab');
document.querySelectorAll('.tab').forEach(tab => {
  tab.addEventListener('click', () => {
    const view = tab.dataset.view;
    if (view === 'timeline') {
      renderTimeline();
    } else if (view === 'performance') {
      renderPerformance();
    }
  });
});
