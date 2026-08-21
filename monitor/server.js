'use strict';

const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const AdbManager = require('./adb-manager');
const LogParser = require('./log-parser');
const NetworkMonitor = require('./network-monitor');

// === 配置 ===
const PORT = 8089;
const MAX_LOG_BUFFER = 5000;  // 每设备最多缓存 5000 条日志
const MAX_BROADCAST_BATCH = 200; // 每批最多广播 200 条

// === 初始化 ===
const adb = new AdbManager();
const parser = new LogParser();
const netMonitor = new NetworkMonitor();

// 设备网络信息缓存：serial -> networkInfo
const deviceNetworks = new Map();

// 设备日志缓存：serial -> { logs: [], stats: {} }
const deviceLogBuffers = new Map();

// WebSocket 客户端集合
const wsClients = new Set();

// 日志广播队列（批量推送，减少开销）
let broadcastQueue = [];
let broadcastTimer = null;

// === HTTP 服务器（静态文件 + WebSocket 升级） ===
const server = http.createServer((req, res) => {
  // CORS
  res.setHeader('Access-Control-Allow-Origin', '*');

  if (req.url === '/api/devices') {
    res.setHeader('Content-Type', 'application/json');
    res.end(JSON.stringify(adb.getDevices().map(d => ({
      serial: d.serial,
      state: d.state,
      model: d.props?.model || 'Unknown',
      brand: d.props?.brand || '',
      android: d.props?.release || '',
      abi: d.props?.abi || '',
    }))));
    return;
  }

  if (req.url === '/api/stats') {
    res.setHeader('Content-Type', 'application/json');
    res.end(JSON.stringify(parser.getStats()));
    return;
  }

  // 静态文件服务
  let filePath = req.url === '/' ? '/index.html' : req.url;
  const ext = path.extname(filePath);
  const mimeTypes = {
    '.html': 'text/html; charset=utf-8',
    '.css': 'text/css; charset=utf-8',
    '.js': 'application/javascript; charset=utf-8',
    '.json': 'application/json',
  };

  filePath = path.join(__dirname, 'public', filePath);
  fs.readFile(filePath, (err, data) => {
    if (err) {
      res.writeHead(404);
      res.end('Not Found');
      return;
    }
    res.setHeader('Content-Type', mimeTypes[ext] || 'application/octet-stream');
    res.end(data);
  });
});

// === WebSocket 升级处理 ===
server.on('upgrade', (req, socket) => {
  const key = req.headers['sec-websocket-key'];
  const accept = crypto.createHash('sha1').update(key + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').digest('base64');

  socket.write(
    'HTTP/1.1 101 Switching Protocols\r\n' +
    'Upgrade: websocket\r\n' +
    'Connection: Upgrade\r\n' +
    'Sec-WebSocket-Accept: ' + accept + '\r\n\r\n'
  );

  const client = { socket, alive: true };
  wsClients.add(client);

  socket.on('data', (data) => {
    // 简单处理客户端消息（心跳 ping）
    try {
      const msg = parseWsFrame(data);
      if (msg) {
        if (msg.type === 'ping') {
          sendWsFrame(socket, { type: 'pong' });
        } else if (msg.type === 'requestHistory') {
          // 发送历史日志
          sendHistory(client);
        }
      }
    } catch (e) {
      // 忽略解析错误
    }
  });

  socket.on('end', () => {
    wsClients.delete(client);
  });

  socket.on('error', () => {
    wsClients.delete(client);
  });

  // 发送初始数据
  sendWsFrame(socket, {
    type: 'init',
    devices: adb.getDevices().map(d => ({
      serial: d.serial,
      model: d.props?.model || 'Unknown',
      brand: d.props?.brand || '',
    })),
    stats: parser.getStats(),
    networks: Object.fromEntries(deviceNetworks),
  });
});

// === WebSocket 帧解析 ===
function parseWsFrame(buffer) {
  if (buffer.length < 2) return null;
  const opcode = buffer[0] & 0x0f;
  const masked = (buffer[1] & 0x80) !== 0;
  let payloadLen = buffer[1] & 0x7f;
  let offset = 2;

  if (payloadLen === 126) {
    payloadLen = buffer.readUInt16BE(2);
    offset = 4;
  } else if (payloadLen === 127) {
    payloadLen = Number(buffer.readBigUInt64BE(2));
    offset = 10;
  }

  let payload;
  if (masked) {
    const mask = buffer.slice(offset, offset + 4);
    offset += 4;
    payload = Buffer.alloc(payloadLen);
    for (let i = 0; i < payloadLen; i++) {
      payload[i] = buffer[offset + i] ^ mask[i % 4];
    }
  } else {
    payload = buffer.slice(offset, offset + payloadLen);
  }

  if (opcode === 8) return { type: 'close' }; // close
  if (opcode === 9) return { type: 'ping' };  // ping
  if (opcode === 10) return { type: 'pong' }; // pong
  if (opcode === 1) {
    // text frame
    return { type: 'message', data: payload.toString('utf8') };
  }
  return null;
}

// === WebSocket 帧发送 ===
function sendWsFrame(socket, obj) {
  const payload = Buffer.from(JSON.stringify(obj), 'utf8');
  const masked = false;
  const len = payload.length;

  let header;
  if (len < 126) {
    header = Buffer.alloc(2);
    header[0] = 0x81; // FIN + text frame
    header[1] = masked ? 0x80 | len : len;
  } else if (len < 65536) {
    header = Buffer.alloc(4);
    header[0] = 0x81;
    header[1] = masked ? 0x80 | 126 : 126;
    header.writeUInt16BE(len, 2);
  } else {
    header = Buffer.alloc(10);
    header[0] = 0x81;
    header[1] = masked ? 0x80 | 127 : 127;
    header.writeBigUInt64BE(BigInt(len), 2);
  }

  try {
    socket.write(Buffer.concat([header, payload]));
  } catch (e) {
    // socket 已关闭
  }
}

// === 广播日志到所有 WebSocket 客户端 ===
function enqueueBroadcast(logEntry) {
  broadcastQueue.push(logEntry);

  if (!broadcastTimer) {
    broadcastTimer = setTimeout(flushBroadcast, 100); // 100ms 批量刷新
  }

  // 防止队列过大
  if (broadcastQueue.length >= MAX_BROADCAST_BATCH) {
    flushBroadcast();
  }
}

function flushBroadcast() {
  if (broadcastTimer) {
    clearTimeout(broadcastTimer);
    broadcastTimer = null;
  }

  if (broadcastQueue.length === 0) return;

  const batch = broadcastQueue.splice(0, MAX_BROADCAST_BATCH);

  for (const client of wsClients) {
    sendWsFrame(client.socket, {
      type: 'logs',
      logs: batch,
    });
  }
}

// === 发送历史日志 ===
function sendHistory(client) {
  for (const [serial, buffer] of deviceLogBuffers) {
    const logs = buffer.logs.slice(-200); // 最近 200 条
    if (logs.length > 0) {
      sendWsFrame(client.socket, {
        type: 'history',
        serial,
        logs,
      });
    }
  }
}

// === ADB 事件处理 ===
adb.on('deviceConnected', (info) => {
  console.log(`[Monitor] 设备已连接: ${info.serial} (${info.state})`);
  deviceLogBuffers.set(info.serial, { logs: [], stats: {} });

  // 启动网络监控
  netMonitor.setAdbPath(adb.adbPath);
  netMonitor.startMonitoring(info.serial);

  for (const client of wsClients) {
    sendWsFrame(client.socket, {
      type: 'deviceConnected',
      device: {
        serial: info.serial,
        state: info.state,
      },
    });
  }
});

adb.on('deviceUpdated', (info) => {
  for (const client of wsClients) {
    sendWsFrame(client.socket, {
      type: 'deviceUpdated',
      device: {
        serial: info.serial,
        model: info.props?.model || 'Unknown',
        brand: info.props?.brand || '',
        android: info.props?.release || '',
        abi: info.props?.abi || '',
      },
    });
  }
});

adb.on('deviceDisconnected', (info) => {
  console.log(`[Monitor] 设备已断开: ${info.serial}`);
  deviceLogBuffers.delete(info.serial);
  deviceNetworks.delete(info.serial);

  // 停止网络监控
  netMonitor.stopMonitoring(info.serial);

  for (const client of wsClients) {
    sendWsFrame(client.socket, {
      type: 'deviceDisconnected',
      serial: info.serial,
    });
  }
});

adb.on('log', ({ serial, raw }) => {
  const entry = parser.parse(raw);
  entry.device = serial;

  // 缓存
  const buffer = deviceLogBuffers.get(serial);
  if (buffer) {
    buffer.logs.push(entry);
    if (buffer.logs.length > MAX_LOG_BUFFER) {
      buffer.logs.splice(0, buffer.logs.length - MAX_LOG_BUFFER);
    }
  }

  enqueueBroadcast(entry);
});

adb.on('logError', ({ serial, error }) => {
  console.error(`[Monitor] logcat 错误 (${serial}): ${error}`);
});

adb.on('error', (msg) => {
  console.error(`[Monitor] ADB 错误: ${msg}`);
});

// === 网络监控事件 ===
netMonitor.on('networkUpdate', ({ serial, network }) => {
  deviceNetworks.set(serial, network);

  for (const client of wsClients) {
    sendWsFrame(client.socket, {
      type: 'networkUpdate',
      serial,
      network,
    });
  }
});

// === 启动 ===
server.listen(PORT, () => {
  console.log('');
  console.log('╔══════════════════════════════════════════╗');
  console.log('║     TV Live 实时日志监控面板              ║');
  console.log('╠══════════════════════════════════════════╣');
  console.log(`║  监控端口: http://localhost:${PORT}          ║`);
  console.log('║  监控包名: com.tv.live                   ║');
  console.log('║  日志分类: 崩溃/解析/播放/源/安全/系统/调试 ║');
  console.log('╚══════════════════════════════════════════╝');
  console.log('');
  console.log('请在浏览器中打开上面的地址');
  console.log('');

  adb.start().catch(err => {
    console.error('ADB 启动失败:', err.message);
    console.error('请确保 ADB 已安装并在 PATH 中，或设置 ANDROID_HOME 环境变量');
  });
});

// === 优雅退出 ===
process.on('SIGINT', () => {
  console.log('\n正在关闭...');
  netMonitor.stopAll();
  adb.stop();
  server.close();
  process.exit(0);
});

process.on('SIGTERM', () => {
  netMonitor.stopAll();
  adb.stop();
  server.close();
  process.exit(0);
});
