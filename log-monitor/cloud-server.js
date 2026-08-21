/**
 * TV Live 云端日志中转服务器
 * 
 * 部署方式：
 * 1. 云服务器（推荐）：部署到任何支持 Node.js 的云服务器（阿里云、腾讯云、AWS 等）
 * 2. Serverless：可改造为 Vercel/Cloudflare Workers 无服务器部署
 * 
 * 使用方法：
 * 1. 将此文件部署到云服务器
 * 2. 手机端应用配置云端服务器地址
 * 3. 电脑端监控工具连接同一云端服务器
 */

const WebSocket = require('ws');
const express = require('express');
const cors = require('cors');
const http = require('http');
const path = require('path');
const fs = require('fs');
const os = require('os');

const PORT = process.env.PORT || 8080;
const PUBLIC_DIR = process.env.PUBLIC_DIR || path.join(__dirname, 'public');

const app = express();
app.use(cors());
app.use(express.json({ limit: '10mb' }));
app.use(express.static(PUBLIC_DIR));

const server = http.createServer(app);
const wss = new WebSocket.Server({ server, path: '/ws' });

// 设备管理
const devices = new Map(); // deviceId -> deviceInfo
const clientConnections = new Set(); // 电脑端监控连接
const MAX_LOGS_PER_DEVICE = 10000;
const logsBuffer = new Map(); // deviceId -> logs[]

// API 密钥（可选，用于安全认证）
const API_KEY = process.env.API_KEY || '';

// 中间件：API 密钥认证
function authenticate(req, res, next) {
  if (API_KEY && req.headers['x-api-key'] !== API_KEY) {
    return res.status(401).json({ error: 'Unauthorized' });
  }
  next();
}

/**
 * 设备注册接口
 * 手机端应用首次连接时调用
 */
app.post('/api/device/register', authenticate, (req, res) => {
  const { deviceId, deviceName, deviceModel, appVersion, brand, manufacturer } = req.body;
  
  if (!deviceId) {
    return res.status(400).json({ error: 'deviceId required' });
  }
  
  devices.set(deviceId, {
    deviceId,
    deviceName: deviceName || 'Unknown',
    deviceModel: deviceModel || 'Unknown',
    appVersion: appVersion || 'Unknown',
    brand: brand || '',
    manufacturer: manufacturer || '',
    registeredAt: new Date().toISOString(),
    lastActive: Date.now(),
    logCount: 0
  });
  
  // 初始化日志缓冲区
  if (!logsBuffer.has(deviceId)) {
    logsBuffer.set(deviceId, []);
  }
  
  res.json({ 
    success: true, 
    deviceId,
    message: 'Device registered successfully'
  });
});

/**
 * 发送日志接口（HTTP 方式，用于手机端应用主动推送）
 */
app.post('/api/logs/:deviceId', authenticate, (req, res) => {
  const deviceId = req.params.deviceId;
  const logs = req.body.logs || [req.body];
  
  if (!Array.isArray(logs)) {
    return res.status(400).json({ error: 'logs must be an array' });
  }
  
  // 获取或创建日志缓冲区
  if (!logsBuffer.has(deviceId)) {
    logsBuffer.set(deviceId, []);
  }
  
  const deviceLogs = logsBuffer.get(deviceId);
  const newLogs = [];
  
  for (const log of logs) {
    const logEntry = {
      ...log,
      deviceId,
      serverTime: new Date().toISOString(),
      receivedAt: Date.now()
    };
    
    deviceLogs.push(logEntry);
    newLogs.push(logEntry);
    
    // 限制日志数量
    if (deviceLogs.length > MAX_LOGS_PER_DEVICE) {
      deviceLogs.shift();
    }
  }
  
  // 更新设备信息
  const device = devices.get(deviceId);
  if (device) {
    device.lastActive = Date.now();
    device.logCount += newLogs.length;
  }
  
  // 实时推送给所有已连接的电脑端监控
  broadcastToClients({
    type: 'logs_batch',
    deviceId,
    logs: newLogs
  });
  
  res.json({ 
    success: true, 
    received: newLogs.length 
  });
});

/**
 * 获取设备列表
 */
app.get('/api/devices', authenticate, (req, res) => {
  const deviceList = [];
  const now = Date.now();
  
  for (const [id, device] of devices) {
    // 只返回最近1小时内活跃的设备
    if (now - device.lastActive < 3600 * 1000) {
      deviceList.push({
        ...device,
        connected: wss.clients.size > 0,
        bufferedLogs: (logsBuffer.get(id) || []).length
      });
    }
  }
  
  res.json({ 
    devices: deviceList, 
    count: deviceList.length,
    totalDevices: devices.size
  });
});

/**
 * 获取指定设备的历史日志
 */
app.get('/api/logs/:deviceId', authenticate, (req, res) => {
  const deviceId = req.params.deviceId;
  const limit = parseInt(req.query.limit) || 500;
  const offset = parseInt(req.query.offset) || 0;
  const type = req.query.type;
  
  let logs = logsBuffer.get(deviceId) || [];
  
  // 按类型过滤
  if (type) {
    logs = logs.filter(l => (l.logType || l.type) === type);
  }
  
  // 分页
  const total = logs.length;
  const pagedLogs = logs.slice(offset, offset + limit);
  
  res.json({ 
    logs: pagedLogs, 
    total,
    deviceId,
    hasMore: offset + limit < total
  });
});

/**
 * 获取所有设备的最新日志
 */
app.get('/api/logs', authenticate, (req, res) => {
  const limit = parseInt(req.query.limit) || 200;
  const deviceId = req.query.deviceId;
  const type = req.query.type;
  
  let allLogs = [];
  
  if (deviceId) {
    allLogs = logsBuffer.get(deviceId) || [];
  } else {
    // 从所有设备获取最新日志
    for (const [id, deviceLogs] of logsBuffer) {
      allLogs = allLogs.concat(deviceLogs.slice(-100));
    }
  }
  
  // 按类型过滤
  if (type) {
    allLogs = allLogs.filter(l => (l.logType || l.type) === type);
  }
  
  // 按时间排序，取最新的
  allLogs.sort((a, b) => (b.timestamp || 0) - (a.timestamp || 0));
  const limitedLogs = allLogs.slice(0, limit);
  
  res.json({ 
    logs: limitedLogs, 
    total: allLogs.length 
  });
});

/**
 * 清空设备日志
 */
app.delete('/api/logs/:deviceId', authenticate, (req, res) => {
  const deviceId = req.params.deviceId;
  const deleted = logsBuffer.get(deviceId) || [];
  
  logsBuffer.set(deviceId, []);
  
  broadcastToClients({
    type: 'logs_cleared',
    deviceId
  });
  
  res.json({ 
    success: true, 
    cleared: deleted.length 
  });
});

/**
 * 清空所有日志
 */
app.delete('/api/logs', authenticate, (req, res) => {
  let totalCleared = 0;
  
  for (const [id, logs] of logsBuffer) {
    totalCleared += logs.length;
    logsBuffer.set(id, []);
  }
  
  broadcastToClients({ type: 'all_logs_cleared' });
  
  res.json({ 
    success: true, 
    totalCleared 
  });
});

/**
 * 获取服务器状态
 */
app.get('/api/status', (req, res) => {
  res.json({
    status: 'ok',
    uptime: process.uptime(),
    connectedClients: clientConnections.size,
    registeredDevices: devices.size,
    totalBufferedLogs: Array.from(logsBuffer.values()).reduce((sum, logs) => sum + logs.length, 0),
    serverTime: new Date().toISOString(),
    version: '1.0.0'
  });
});

/**
 * WebSocket 处理
 * 用于电脑端监控工具实时接收日志
 */
wss.on('connection', (ws, req) => {
  const url = req.url;
  const params = new URLSearchParams(url.split('?')[1] || '');
  const requestedDeviceId = params.get('deviceId');
  const apiKey = params.get('apiKey');
  
  // 认证检查
  if (API_KEY && apiKey !== API_KEY) {
    ws.close(4001, 'Unauthorized');
    return;
  }
  
  clientConnections.add(ws);
  
  ws.send(JSON.stringify({
    type: 'connected',
    message: 'Connected to TV Live Cloud Log Server',
    serverTime: new Date().toISOString(),
    subscribedDevice: requestedDeviceId || 'all'
  }));
  
  // 如果请求特定设备的日志，发送历史日志
  if (requestedDeviceId && logsBuffer.has(requestedDeviceId)) {
    const historyLogs = logsBuffer.get(requestedDeviceId).slice(-500);
    ws.send(JSON.stringify({
      type: 'history_logs',
      deviceId: requestedDeviceId,
      logs: historyLogs
    }));
  } else if (!requestedDeviceId) {
    // 发送所有设备的最新日志
    const allLogs = [];
    for (const [id, logs] of logsBuffer) {
      allLogs.push(...logs.slice(-50));
    }
    allLogs.sort((a, b) => (b.timestamp || 0) - (a.timestamp || 0));
    
    ws.send(JSON.stringify({
      type: 'history_logs',
      logs: allLogs.slice(0, 200)
    }));
  }
  
  // 发送设备列表
  const deviceList = [];
  for (const [id, device] of devices) {
    deviceList.push({
      id: device.deviceId,
      info: device,
      connected: true
    });
  }
  
  ws.send(JSON.stringify({
    type: 'devices_list',
    devices: deviceList
  }));
  
  // 处理客户端消息
  ws.on('message', (data) => {
    try {
      const message = JSON.parse(data.toString());
      handleClientMessage(ws, message);
    } catch (e) {
      console.error('Invalid WebSocket message:', e.message);
    }
  });
  
  ws.on('close', () => {
    clientConnections.delete(ws);
    console.log(`[WebSocket] Client disconnected. Total: ${clientConnections.size}`);
  });
  
  ws.on('error', (err) => {
    console.error('WebSocket error:', err.message);
  });
});

/**
 * 处理客户端消息
 */
function handleClientMessage(ws, message) {
  switch (message.type) {
    case 'ping':
      ws.send(JSON.stringify({ 
        type: 'pong', 
        timestamp: Date.now() 
      }));
      break;
      
    case 'get_devices':
      const deviceList = [];
      for (const [id, device] of devices) {
        deviceList.push({
          id: device.deviceId,
          info: device,
          connected: true
        });
      }
      ws.send(JSON.stringify({ 
        type: 'devices_list', 
        devices: deviceList 
      }));
      break;
      
    case 'get_logs':
      const { deviceId, limit = 500, offset = 0 } = message;
      const logs = deviceId 
        ? (logsBuffer.get(deviceId) || []) 
        : [];
      const pagedLogs = logs.slice(offset, offset + limit);
      
      ws.send(JSON.stringify({ 
        type: 'logs', 
        deviceId,
        logs: pagedLogs,
        total: logs.length 
      }));
      break;
      
    case 'subscribe':
      // 电脑端订阅特定设备的日志
      ws.subscribedDevice = message.deviceId;
      ws.send(JSON.stringify({ 
        type: 'subscribed', 
        deviceId: message.deviceId 
      }));
      break;
      
    case 'clear_logs':
      const clearDeviceId = message.deviceId;
      if (clearDeviceId && logsBuffer.has(clearDeviceId)) {
        logsBuffer.set(clearDeviceId, []);
        broadcastToClients({
          type: 'logs_cleared',
          deviceId: clearDeviceId
        });
      }
      break;
  }
}

/**
 * 广播消息给所有电脑端监控连接
 */
function broadcastToClients(message) {
  const data = JSON.stringify(message);
  for (const client of clientConnections) {
    if (client.readyState === WebSocket.OPEN) {
      try {
        // 检查是否需要过滤设备
        if (client.subscribedDevice && message.deviceId && 
            client.subscribedDevice !== 'all' && 
            client.subscribedDevice !== message.deviceId) {
          continue;
        }
        client.send(data);
      } catch (e) {}
    }
  }
}

/**
 * 清理过期设备
 */
setInterval(() => {
  const now = Date.now();
  let removed = 0;
  
  for (const [id, device] of devices) {
    // 超过24小时未活跃的设备
    if (now - device.lastActive > 24 * 3600 * 1000) {
      devices.delete(id);
      logsBuffer.delete(id);
      removed++;
    }
  }
  
  if (removed > 0) {
    console.log(`[Cleanup] Removed ${removed} stale devices`);
  }
}, 3600 * 1000); // 每小时执行一次

// 获取本机局域网 IP
function getLocalIPs() {
  const interfaces = os.networkInterfaces();
  const ips = [];
  for (const name of Object.keys(interfaces)) {
    for (const iface of interfaces[name]) {
      // 跳过 IPv6 和回环地址
      if (iface.family === 'IPv4' && !iface.internal) {
        ips.push(iface.address);
      }
    }
  }
  return ips;
}

// 启动服务器
server.listen(PORT, '0.0.0.0', () => {
  const localIPs = getLocalIPs();
  const lanIP = localIPs[0] || '未知';
  
  console.log('');
  console.log('╔════════════════════════════════════════════════════════════════════╗');
  console.log('║          TV Live 日志监控 - 本地远程服务                             ║');
  console.log('╠════════════════════════════════════════════════════════════════════╣');
  console.log('║                                                                    ║');
  console.log('║  [本机访问]                                                        ║');
  console.log(`║    电脑端监控页面: http://localhost:${PORT}                          ║`);
  console.log(`║    API 地址:       http://localhost:${PORT}/api/status              ║`);
  console.log('║                                                                    ║');
  console.log('║  [局域网访问 - 同一 WiFi 下的手机可直接连接]                         ║');
  localIPs.forEach(ip => {
    console.log(`║    手机连接地址:  http://${ip}:${PORT}                              `);
  });
  console.log('║                                                                    ║');
  console.log('║  [外网访问 - 需要内网穿透]                                          ║');
  console.log('║    1. 下载 cpolar: https://www.cpolar.com/download                 ║');
  console.log('║    2. 运行: cpolar http ' + PORT + '                                    ║');
  console.log('║    3. 获得公网地址后填入 App 即可                                    ║');
  console.log('║                                                                    ║');
  console.log('╠════════════════════════════════════════════════════════════════════╣');
  console.log('║  [API 接口]                                                        ║');
  console.log('║    设备注册: POST /api/device/register                             ║');
  console.log('║    发送日志: POST /api/logs/:deviceId                              ║');
  console.log('║    获取日志: GET  /api/logs                                         ║');
  console.log('║    设备列表: GET  /api/devices                                     ║');
  console.log('║    清空日志: DELETE /api/logs                                      ║');
  console.log('║    健康检查: GET  /api/status                                      ║');
  console.log('║    WebSocket: ws://地址/ws                                         ║');
  console.log('╠════════════════════════════════════════════════════════════════════╣');
  console.log(`║  已注册设备: ${String(devices.size).padEnd(6)} 缓冲日志: ${String(Array.from(logsBuffer.values()).reduce((sum, l) => sum + l.length, 0)).padEnd(6)} 客户端: ${String(clientConnections.size).padEnd(4)}   ║`);
  console.log('╚════════════════════════════════════════════════════════════════════╝');
  console.log('');
  console.log('  等待手机 App 连接...');
  console.log('');
});

module.exports = app;
