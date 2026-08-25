// 日志生成器 - 用于模拟大量日志测试
const WebSocket = require('ws');

const ws = new WebSocket('ws://localhost:3000');

const TYPES = ['info', 'network', 'playback', 'debug', 'crash', 'warn', 'error'];
const TAGS = ['TVLive', 'TVPlayerManager', 'LogServer', 'HuyaSDKLogger', 'MediaPlayer', 'DecoderModeManager', 'SourceHealthChecker', 'BootReceiver', 'MyApplication', 'MainActivity'];
const MESSAGES = [
  'Failed to send HTTP logs: Failed to connect to /192.168.1.17:8080',
  'tagSocket(261) with statsTag=0xffffffff, statsUid=-1',
  'bufferpool2 0x7d027c3328 : 5(20480 size) total buffers - 1(4096 size) used buffers - 3491/3496 (recycle/alloc) - 13/3495 (fetch/transfer)',
  'onPrepared: state=PREPARED',
  'onRenderingStart',
  'stall detected: duration=1500ms',
  'decode error: frame corrupted',
  'Network request completed: latency=235ms',
  'Cache hit ratio: 87.5%',
  'Player state changed: IDLE -> PREPARING',
  'Low memory warning: 512MB available',
  'Codec initialized: H.264 HW decoder',
  'Surface texture updated: 1920x1080',
  'Session restored: 127 items',
  'Bitrate adaptation: 1080p -> 720p',
  'DNS resolution: 23ms',
  'TLS handshake: 45ms',
  'Request timeout after 30000ms',
  'Connection pool: 8/10 active',
  'Buffer underrun: waiting for data',
];

function randomItem(arr) {
  return arr[Math.floor(Math.random() * arr.length)];
}

function generateLog() {
  const type = randomItem(TYPES);
  const tag = randomItem(TAGS);
  const message = randomItem(MESSAGES);
  return {
    type: 'log',
    log: {
      logType: type,
      deviceId: 'emulator-5554',
      message: `[ADB: emulator-5554] ${message}`,
      tag: tag,
      timestamp: Date.now(),
      serverTime: new Date().toISOString()
    }
  };
}

let count = 0;
const BATCH_SIZE = 500;
const TARGET = 100000;

ws.on('open', () => {
  console.log('WebSocket connected, generating logs...');
  
  function sendBatch() {
    for (let i = 0; i < BATCH_SIZE && count < TARGET; i++) {
      ws.send(JSON.stringify(generateLog()));
      count++;
    }
    
    if (count % 10000 === 0) {
      console.log(`Generated ${count} logs...`);
    }
    
    if (count < TARGET) {
      setImmediate(sendBatch);
    } else {
      console.log(`Done! Generated ${count} logs total.`);
      ws.close();
    }
  }
  
  sendBatch();
});

ws.on('error', (err) => {
  console.error('WebSocket error:', err.message);
  process.exit(1);
});

// 超时退出
setTimeout(() => {
  console.log('Timeout, exiting...');
  process.exit(0);
}, 60000);