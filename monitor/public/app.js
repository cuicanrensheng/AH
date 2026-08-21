'use strict';

// ===== 全局状态 =====
const state = {
  ws: null,
  connected: false,
  paused: false,
  autoScroll: true,
  selectedDevice: '__all__',
  selectedCategory: 'all',
  selectedLevel: 'all',
  searchText: '',
  logs: [],           // 全部日志
  deviceLogs: {},     // serial -> [logs]
  devices: {},         // serial -> deviceInfo
  networks: {},        // serial -> networkInfo
  stats: {},
  maxLogs: 10000,
};

// ===== DOM 元素 =====
const $ = (id) => document.getElementById(id);
const logContainer = $('logContainer');

// ===== WebSocket 连接 =====
function connectWebSocket() {
  const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
  const wsUrl = `${protocol}//${location.hostname}:${location.port}`;

  state.ws = new WebSocket(wsUrl);

  state.ws.onopen = () => {
    state.connected = true;
    $('wsStatus').classList.add('connected');
    $('wsStatusText').textContent = '已连接';
    // 请求历史日志
    state.ws.send(JSON.stringify({ type: 'requestHistory' }));
  };

  state.ws.onclose = () => {
    state.connected = false;
    $('wsStatus').classList.remove('connected');
    $('wsStatusText').textContent = '已断开，重连中...';
    setTimeout(connectWebSocket, 3000);
  };

  state.ws.onerror = () => {
    state.ws.close();
  };

  state.ws.onmessage = (event) => {
    const msg = JSON.parse(event.data);
    handleMessage(msg);
  };
}

// ===== 消息处理 =====
function handleMessage(msg) {
  switch (msg.type) {
    case 'init':
      state.stats = msg.stats || {};
      msg.devices?.forEach(d => {
        state.devices[d.serial] = d;
        if (!state.deviceLogs[d.serial]) {
          state.deviceLogs[d.serial] = [];
        }
      });
      if (msg.networks) {
        for (const [serial, net] of Object.entries(msg.networks)) {
          state.networks[serial] = net;
        }
      }
      updateDeviceList();
      updateStats();
      renderNetworkPanel();
      break;

    case 'deviceConnected':
      state.devices[msg.device.serial] = msg.device;
      state.deviceLogs[msg.device.serial] = [];
      updateDeviceList();
      break;

    case 'deviceUpdated':
      state.devices[msg.device.serial] = { ...state.devices[msg.device.serial], ...msg.device };
      updateDeviceList();
      break;

    case 'deviceDisconnected':
      delete state.devices[msg.serial];
      delete state.networks[msg.serial];
      updateDeviceList();
      renderNetworkPanel();
      break;

    case 'networkUpdate':
      if (msg.serial && msg.network) {
        state.networks[msg.serial] = msg.network;
        renderNetworkPanel();
      }
      break;

    case 'logs':
      msg.logs.forEach(log => addLog(log));
      break;

    case 'history':
      if (msg.logs && msg.logs.length > 0) {
        msg.logs.forEach(log => addLog(log, false));
      }
      renderLogs();
      break;

    case 'networkUpdate':
      state.networks[msg.serial] = msg.network;
      renderNetworkPanel();
      break;
  }
}

// ===== 添加日志 =====
function addLog(log, render = true) {
  // 全局缓存
  state.logs.push(log);
  if (state.logs.length > state.maxLogs) {
    state.logs.splice(0, state.logs.length - state.maxLogs);
  }

  // 设备缓存
  if (!state.deviceLogs[log.device]) {
    state.deviceLogs[log.device] = [];
  }
  state.deviceLogs[log.device].push(log);
  if (state.deviceLogs[log.device].length > state.maxLogs) {
    state.deviceLogs[log.device].splice(0, state.deviceLogs[log.device].length - state.maxLogs);
  }

  // 更新统计
  if (!state.stats[log.category]) state.stats[log.category] = 0;
  state.stats[log.category]++;

  // 更新设备日志计数
  const countEl = $(`device-count-${log.device}`);
  if (countEl) {
    countEl.textContent = state.deviceLogs[log.device].length;
  }

  if (render && !state.paused) {
    appendLogRow(log);
    updateStats();
  }
}

// ===== 渲染日志 =====
function renderLogs() {
  logContainer.innerHTML = '';
  const logs = getFilteredLogs();
  const fragment = document.createDocumentFragment();
  for (const log of logs) {
    fragment.appendChild(createLogRow(log));
  }
  logContainer.appendChild(fragment);
  updateStats();
  if (state.autoScroll) {
    logContainer.scrollTop = logContainer.scrollHeight;
  }
}

function appendLogRow(log) {
  // 检查过滤条件
  if (!matchesFilter(log)) return;

  const row = createLogRow(log);
  logContainer.appendChild(row);

  // 限制 DOM 条数
  if (logContainer.children.length > state.maxLogs) {
    logContainer.removeChild(logContainer.firstChild);
  }

  if (state.autoScroll) {
    logContainer.scrollTop = logContainer.scrollHeight;
  }

  updateVisibleCount();
}

function createLogRow(log) {
  const row = document.createElement('div');
  row.className = `log-row ${log.category}`;
  row.dataset.category = log.category;
  row.dataset.level = log.level;
  row.dataset.device = log.device || '';
  row.dataset.tag = log.tag || '';
  row.dataset.message = (log.message || '').toLowerCase();

  row.innerHTML = `
    <span class="log-time">${log.timestamp || ''}</span>
    <span class="log-device">${log.device ? log.device.substring(0, 12) : ''}</span>
    <span class="log-level log-level-${log.level}">${log.level}</span>
    <span class="log-category log-cat-${log.category}">${log.categoryLabel || log.category}</span>
    <span class="log-tag">${escapeHtml(log.tag || '')}</span>
    <span class="log-message" style="color:${log.color || '#ccc'}">${escapeHtml(log.message || '')}</span>
  `;
  return row;
}

// ===== 过滤逻辑 =====
function getFilteredLogs() {
  let logs;
  if (state.selectedDevice === '__all__') {
    logs = state.logs;
  } else {
    logs = state.deviceLogs[state.selectedDevice] || [];
  }

  return logs.filter(matchesFilter);
}

function matchesFilter(log) {
  if (state.selectedDevice !== '__all__' && log.device !== state.selectedDevice) return false;
  if (state.selectedCategory !== 'all' && log.category !== state.selectedCategory) return false;
  if (state.selectedLevel !== 'all' && log.level !== state.selectedLevel) return false;
  if (state.searchText) {
    const text = (log.tag + ' ' + log.message + ' ' + log.raw).toLowerCase();
    if (!text.includes(state.searchText)) return false;
  }
  return true;
}

function filterLogs() {
  state.searchText = $('searchInput').value.toLowerCase().trim();
  renderLogs();
}

// ===== 设备选择 =====
function selectDevice(serial) {
  state.selectedDevice = serial;
  document.querySelectorAll('.device-item').forEach(el => {
    el.classList.toggle('active', el.dataset.serial === serial);
  });
  renderLogs();
}

// ===== 分类选择 =====
function selectCategory(cat) {
  state.selectedCategory = cat;
  document.querySelectorAll('.filter-item[data-category]').forEach(el => {
    el.classList.toggle('active', el.dataset.category === cat);
  });
  renderLogs();
}

// ===== 级别选择 =====
function selectLevel(level) {
  state.selectedLevel = level;
  document.querySelectorAll('.filter-item[data-level]').forEach(el => {
    el.classList.toggle('active', el.dataset.level === level);
  });
  renderLogs();
}

// ===== 更新设备列表 =====
function updateDeviceList() {
  const list = $('deviceList');
  const items = list.querySelectorAll('.device-item:not([data-serial="__all__"])');
  items.forEach(el => el.remove());

  const allCount = state.logs.length;
  $('device-count-__all__').textContent = allCount;

  for (const [serial, info] of Object.entries(state.devices)) {
    const li = document.createElement('li');
    li.className = 'device-item';
    li.dataset.serial = serial;
    li.onclick = () => selectDevice(serial);

    const model = info.model || info.brand || serial;
    const count = state.deviceLogs[serial]?.length || 0;

    li.innerHTML = `
      <span class="device-icon">📱</span>
      <span class="device-name" title="${serial}">${escapeHtml(model)}</span>
      <span class="device-log-count">${count}</span>
    `;

    li.addEventListener('click', () => selectDevice(serial));
    list.appendChild(li);
  }
}

// ===== 渲染网络状态面板 =====
function renderNetworkPanel() {
  const panel = $('networkPanel');
  if (!panel) return;

  const entries = Object.entries(state.networks);
  if (entries.length === 0) {
    panel.innerHTML = '<div class="network-empty">等待设备连接...</div>';
    return;
  }

  panel.innerHTML = '';

  for (const [serial, net] of entries) {
    const device = state.devices[serial] || {};
    const model = device.model || device.brand || serial.substring(0, 12);

    const block = document.createElement('div');
    block.className = 'network-card';

    block.innerHTML = `
      <div class="network-card-header">
        <span class="network-device-name">${escapeHtml(model)}</span>
        <span class="network-type-badge ${net.activeNetwork?.type === 'WIFI' ? 'type-wifi' : 'type-mobile'}">
          ${net.activeNetwork?.type === 'WIFI' ? 'WiFi' : net.activeNetwork?.type === 'MOBILE' ? '流量' : '离线'}
        </span>
        ${net.activeNetwork?.hasInternet ? '<span class="net-online">●</span>' : '<span class="net-offline">●</span>'}
      </div>
      ${renderWifi(net.wifi)}
      ${renderMobile(net.mobile)}
      ${renderProxy(net.proxy)}
    `;

    panel.appendChild(block);
  }
}

function renderWifi(wifi) {
  if (!wifi || !wifi.enabled) {
    return `<div class="network-row disabled">WiFi: <span class="net-off">已关闭</span></div>`;
  }
  let html = `<div class="network-row">WiFi: <span class="net-on">${escapeHtml(wifi.ssid || '已连接')}</span></div>`;
  if (wifi.rssi !== null) {
    const bars = wifiStrengthBars(wifi.rssi);
    html += `<div class="network-row sub">信号: ${bars} (${wifi.rssi} dBm)</div>`;
  }
  if (wifi.ip) html += `<div class="network-row sub">IP: ${escapeHtml(wifi.ip)}</div>`;
  if (wifi.linkSpeed !== null) html += `<div class="network-row sub">速率: ${wifi.linkSpeed} Mbps${wifi.frequency ? ' · ' + wifi.frequency + 'MHz' : ''}</div>`;
  return html;
}

function renderMobile(mobile) {
  if (!mobile || !mobile.enabled) {
    return `<div class="network-row disabled">流量: <span class="net-off">未启用</span></div>`;
  }
  let html = `<div class="network-row">流量: <span class="net-on">${escapeHtml(mobile.carrier || '运营商')}</span></div>`;
  if (mobile.networkType) html += `<div class="network-row sub">类型: ${escapeHtml(mobile.networkType)}</div>`;
  return html;
}

function renderProxy(proxy) {
  if (!proxy) return '';
  return `<div class="network-row proxy">代理: ${escapeHtml(proxy)}</div>`;
}

function wifiStrengthBars(rssi) {
  let level = 0;
  if (rssi >= -50) level = 4;
  else if (rssi >= -60) level = 3;
  else if (rssi >= -70) level = 2;
  else if (rssi >= -80) level = 1;
  const full = '█'.repeat(level);
  const empty = '░'.repeat(4 - level);
  const color = level >= 3 ? '#66cc66' : level >= 2 ? '#ffcc44' : '#ff4444';
  return `<span style="color:${color}">${full}${empty}</span>`;
}

// ===== 更新统计 =====
function updateStats() {
  for (const cat of ['crash', 'parse', 'player', 'source', 'security', 'system', 'debug']) {
    const el = $(`stat-${cat}`);
    if (el) el.textContent = state.stats[cat] || 0;
  }
  updateVisibleCount();
}

function updateVisibleCount() {
  const visible = logContainer.querySelectorAll('.log-row').length;
  $('visibleCount').textContent = visible;
  $('totalCount').textContent = state.logs.length;
}

// ===== 按钮操作 =====
function clearLogs() {
  state.logs = [];
  for (const key of Object.keys(state.deviceLogs)) {
    state.deviceLogs[key] = [];
  }
  state.stats = {};
  logContainer.innerHTML = '<div class="log-empty">日志已清空</div>';
  updateStats();
  updateDeviceList();
}

function togglePause() {
  state.paused = !state.paused;
  const btn = $('pauseBtn');
  if (state.paused) {
    btn.textContent = '继续';
    btn.classList.add('paused');
  } else {
    btn.textContent = '暂停';
    btn.classList.remove('paused');
  }
}

// ===== 自动滚动 =====
$('autoScroll').addEventListener('change', (e) => {
  state.autoScroll = e.target.checked;
});

// ===== 工具函数 =====
function escapeHtml(text) {
  const div = document.createElement('div');
  div.textContent = text;
  return div.innerHTML;
}

// ===== 启动 =====
connectWebSocket();
