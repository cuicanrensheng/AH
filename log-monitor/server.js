const WebSocket = require('ws');
const express = require('express');
const cors = require('cors');
const http = require('http');
const https = require('https');
const path = require('path');
const fs = require('fs');
const os = require('os');
const { exec, spawn } = require('child_process');

const PORT = process.env.PORT || 3000;
const WS_PORT = process.env.WS_PORT || 3001;

const app = express();
app.use(cors());
app.use(express.json());
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
  exec(`"${ADB_PATH}" devices -l`, (error, stdout, stderr) => {
    if (error) {
      res.json({ success: false, error: error.message, devices: [] });
      return;
    }
    
    const lines = stdout.trim().split('\n');
    const devices = [];
    const seenSerials = new Set();
    
    for (let i = 1; i < lines.length; i++) {
      const line = lines[i].trim();
      if (!line) continue;
      
      const parts = line.split(/\s+/);
      if (parts.length >= 2) {
        const serial = parts[0];
        const state = parts[1];
        
        // 只保留状态为 "device" 的设备（过滤掉连接中间状态）
        if (state !== 'device') continue;
        
        // 避免重复添加同一设备
        if (seenSerials.has(serial)) continue;
        seenSerials.add(serial);
        
        const info = {};
        
        for (let j = 2; j < parts.length; j++) {
          const kv = parts[j].split(':');
          if (kv.length === 2) {
            info[kv[0]] = kv[1];
          }
        }
        
        devices.push({
          serial,
          state,
          model: info.model || 'Unknown',
          device: info.device || '',
          product: info.product || '',
          isEmulator: serial.startsWith('emulator-') || serial.startsWith('127.0.0.1')
        });
      }
    }
    
    res.json({ success: true, devices });
  });
});

app.post('/api/adb/connect', async (req, res) => {
  const { serial, autoStart = true } = req.body;
  
  if (!serial) {
    return res.status(400).json({ success: false, error: 'Device serial required' });
  }
  
  const sessionId = `adb_${Date.now()}_${Math.random().toString(36).substr(2, 6)}`;
  const adbDeviceId = `adb_device_${serial.replace(/[^a-zA-Z0-9]/g, '_')}`;
  
  try {
    const adb = `"${ADB_PATH}" -s "${serial}"`;
    const LOCAL_LOG_PORT = 19527;
    
    // 设置端口转发：将电脑的 LOCAL_LOG_PORT 转发到手机的 9527
    exec(`${adb} forward tcp:${LOCAL_LOG_PORT} tcp:9527`, (err) => {
      if (err) {
        console.warn(`[ADB] Port forwarding failed: ${err.message}`);
      } else {
        console.log(`[ADB] Port forwarding set: localhost:${LOCAL_LOG_PORT} -> device:9527`);
      }
    });
    
    // 检查应用是否安装
    exec(`${adb} shell pm list packages | findstr "${TV_LIVE_PACKAGE}"`, (err, stdout) => {
      const appInstalled = stdout.includes(TV_LIVE_PACKAGE);
      
      if (!appInstalled) {
        console.warn(`[ADB] TV Live not installed on ${serial}`);
      }
      
      // 无论应用是否安装，都启动 logcat 抓取日志
      // LogServer 模式会在后台尝试，但 logcat 作为基础保障
      if (autoStart && appInstalled) {
        console.log(`[ADB] Starting TV Live on ${serial}...`);
        exec(`${adb} shell am force-stop ${TV_LIVE_PACKAGE}`, () => {
          exec(`${adb} shell am start -n ${TV_LIVE_PACKAGE}/.MainActivity`, () => {
            console.log(`[ADB] TV Live started on ${serial}`);
          });
        });
      }
      
      // 始终启动 logcat 抓取日志（作为主要日志来源）
      setTimeout(() => {
        console.log(`[ADB] Starting logcat for ${serial}...`);
        startLogcatByTags(adb, serial, adbDeviceId, sessionId);
      }, appInstalled ? 2000 : 500);
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
    
    res.json({ 
      success: true, 
      deviceId: adbDeviceId, 
      sessionId,
      port: LOCAL_LOG_PORT,
      message: `ADB connected to ${serial} (port forwarding: localhost:${LOCAL_LOG_PORT} -> device:9527)` 
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
        parseAndPushAdbLog(adbDeviceId, line);
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
  const { sessionId, deviceId } = req.body;
  
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
    
    exec(pairCmd, { timeout: 15000 }, (err, stdout, stderr) => {
      const output = (stdout || '') + (stderr || '');
      console.log(`[ADB pair] Output: ${output.trim()}`);
      
      if (err && err.killed) {
        return res.status(500).json({ success: false, error: '配对超时，请重试' });
      }
      
      if (output.includes('Successfully paired') || output.includes('already paired')) {
        // 配对成功后自动尝试连接
        console.log(`[ADB] Pairing successful, trying to connect to ${ip}...`);
        
        // 带重试的连接函数
        const connectWithRetry = (attemptsLeft) => {
          if (attemptsLeft <= 0) {
            return res.json({ 
              success: true, 
              message: '配对成功，但自动连接失败。请点击"扫描ADB设备"手动连接',
              autoConnectFailed: true 
            });
          }
          
          console.log(`[ADB] Connection attempt (${4 - attemptsLeft}/3)...`);
          
          exec(`"${ADB_PATH}" connect ${ip}`, { timeout: 5000 }, (err2, stdout2, stderr2) => {
            const connectOutput = (stdout2 || '') + (stderr2 || '');
            console.log(`[ADB connect] ${connectOutput.trim()}`);
            
            if (connectOutput.includes('connected') || connectOutput.includes('already connected')) {
              // 连接成功，获取设备并开始抓取日志
              setTimeout(() => {
                exec(`"${ADB_PATH}" devices -l`, (err3, stdout3) => {
                  const lines = stdout3.trim().split('\n');
                  for (let i = 1; i < lines.length; i++) {
                    const line = lines[i].trim();
                    if (!line) continue;
                    const parts = line.split(/\s+/);
                    if (parts.length >= 2 && parts[1] === 'device' && parts[0].includes(ip)) {
                      const serial = parts[0];
                      console.log(`[ADB] Auto-connected to device: ${serial}`);
                      connectToDeviceAndStartLogging(serial, res);
                      return;
                    }
                  }
                  // 没找到设备，可能需要等待
                  if (attemptsLeft > 1) {
                    console.log(`[ADB] Device not found yet, retrying...`);
                    setTimeout(() => connectWithRetry(attemptsLeft - 1), 1000);
                  } else {
                    res.json({ success: true, message: '配对成功，请稍候自动连接...', autoConnecting: true });
                  }
                });
              }, 500);
            } else if (connectOutput.includes('failed') || connectOutput.includes('cannot')) {
              // 连接失败，重试
              console.log(`[ADB] Connect failed, retrying...`);
              setTimeout(() => connectWithRetry(attemptsLeft - 1), 1000);
            } else {
              // 未知输出，也尝试获取设备列表
              setTimeout(() => {
                exec(`"${ADB_PATH}" devices -l`, (err3, stdout3) => {
                  const lines = stdout3.trim().split('\n');
                  for (let i = 1; i < lines.length; i++) {
                    const line = lines[i].trim();
                    if (!line) continue;
                    const parts = line.split(/\s+/);
                    if (parts.length >= 2 && parts[1] === 'device' && parts[0].includes(ip)) {
                      const serial = parts[0];
                      console.log(`[ADB] Found device: ${serial}`);
                      connectToDeviceAndStartLogging(serial, res);
                      return;
                    }
                  }
                  if (attemptsLeft > 1) {
                    setTimeout(() => connectWithRetry(attemptsLeft - 1), 1000);
                  } else {
                    res.json({ success: true, message: '配对成功，请稍候自动连接...', autoConnecting: true });
                  }
                });
              }, 500);
            }
          });
        };
        
        // 开始第一次连接尝试
        setTimeout(() => connectWithRetry(3), 1500);
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
function connectToDeviceAndStartLogging(serial, res) {
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
  
  res.json({ 
    success: true, 
    deviceId: adbDeviceId, 
    sessionId,
    message: `已连接到 ${serial}` 
  });
}

// 应用相关标签列表（简化版，只包含 TV Live 相关标签）
const APP_TAGS = [
  // TV Live 应用核心标签
  'TVLive', 'CrashHandler', 'LogServer', 'TVPlayerManager', 'LiveSourceLoader',
  'HuyaSDKLogger', 'HuyaSDKParser', 'HuyaStreamPlayer', 'HuyaCacheGov',
  'BootReceiver', 'BootStartManager', 'BootStartReceiver', 'BootJobService',
  'BootStartFgService', 'SourceHealthChecker', 'SourceDialogManager',
  'SecChk', 'RedirectHttp', 'DecoderModeManager', 'DeviceCapabilities',
  'VariantManager', 'AppCacheInspector', 'BootStartForegroundService',
  'MyApplication', 'SecurityCore', 'TVLS', 'MainActivity', 'KEY_DEBUG',
  'tv.live',
  // 常见库标签
  'ExoPlayer', 'OkHttp', 'OkHttpClient', 'Retrofit',
  'MediaPlayer', 'AndroidMediaPlayer', 'SoftwareMediaPlayer',
  'AndroidRuntime', 'RuntimeException',
  'Gson', 'Glide', 'Coil',
  'Coroutine', 'Kotlin',
  'Firebase', 'Bugly', 'Sentry',
  'WebView', 'Chromium',
  'ActivityManager', 'WindowManager',
  'SurfaceView', 'TextureView',
  'BufferQueue', 'SurfaceTexture',
  'AudioTrack', 'AudioFlinger',
  'CameraManager', 'CameraDevice',
  'WifiManager', 'WifiService',
  'Bluetooth', 'BluetoothAdapter',
  'LocationManager', 'LocationProvider',
  'PowerManager', 'PowerManagerService',
  'InputManager', 'InputDispatcher',
  'DisplayManager', 'DisplayDevice',
  'ViewRootImpl', 'Choreographer',
  'ClassLoader', 'DexClassLoader', 'PathClassLoader',
  'DexFile', 'Dex2Oat', 'dex2oat',
  'ART', ' art.', 'art.',
  'GarbageCollector', 'ConcurrentCopying',
  'Binder', 'BinderProxy', 'BinderDriver',
  'AIDL', 'Parcel',
  'Zygote', 'app_process', 'system_server',
  'init', 'adbd', 'logcat',
  'emulator', 'genymotion', 'vbox',
  'DEBUG', 'System.err', 'System.out'
];

// 统计日志数量
let totalParsedLogs = 0;

function parseAndPushAdbLog(deviceId, line) {
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
    const isAppLog = tag && APP_TAGS.some(t => tag.includes(t));
    logEntry.isAppLog = !!isAppLog;
    logEntry.isSystemLog = !isAppLog;
    
    // 判断日志级别
    if (level === 'E' || logLine.includes('ERROR') || logLine.includes('FATAL EXCEPTION')) {
      logEntry.logType = (logLine.includes('FATAL EXCEPTION') || logLine.includes('AndroidRuntime')) ? 'crash' : 'error';
    } else if (level === 'W') {
      logEntry.logType = 'warn';
    } else if (level === 'D') {
      logEntry.logType = 'debug';
    } else if (level === 'V') {
      logEntry.logType = 'debug';
    } else {
      logEntry.logType = 'info';
    }
    
    // 特殊标记 - 崩溃
    if (logLine.includes('FATAL EXCEPTION') || logLine.includes('AndroidRuntime') || logLine.includes('CrashHandler')) {
      logEntry.logType = 'crash';
      logEntry.type = 'crash';
      if (!logEntry.message.startsWith('💥')) {
        logEntry.message = '💥 CRASH DETECTED:\n' + logEntry.message;
      }
    }
    
    // 特殊标记 - 网络相关
    if (tag === 'OkHttp' || tag === 'Retrofit' || tag === 'RedirectHttp' || 
        message.includes('http') || message.includes('网络') || message.includes('请求')) {
      logEntry.logType = 'network';
    }
    
    // 特殊标记 - 播放相关
    if (tag === 'TVPlayerManager' || tag === 'HuyaStreamPlayer' || tag === 'MediaPlayer' ||
        tag === 'ExoPlayer' || tag === 'IjkMediaPlayer' ||
        message.includes('播放') || message.includes('player') || message.includes('stream')) {
      logEntry.logType = 'playback';
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
app.post('/api/adb/install', async (req, res) => {
  const { serial, apkPath, apkData } = req.body;
  if (!serial) return res.status(400).json({ success: false, error: 'Serial required' });

  let filePath = apkPath;
  
  if (apkData && apkData.startsWith('data:')) {
    const base64Data = apkData.replace(/^data:[^;]*;base64,/, '');
    const fileName = apkPath || `tv_live_install_${Date.now()}.apk`;
    filePath = path.join(os.tmpdir(), fileName);
    fs.writeFileSync(filePath, Buffer.from(base64Data, 'base64'));
  }

  if (!filePath) return res.status(400).json({ success: false, error: 'APK path required' });

  exec(`"${ADB_PATH}" -s "${serial}" install -r "${filePath}"`, { timeout: 120000 }, (err, stdout, stderr) => {
    if (apkData && filePath.startsWith(os.tmpdir())) {
      fs.unlink(filePath, () => {});
    }
    if (err) return res.json({ success: false, error: stderr || err.message });
    res.json({ success: true, output: stdout.trim() });
  });
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
app.post('/api/adb/push', (req, res) => {
  const { serial, localPath, remotePath } = req.body;
  if (!serial) return res.status(400).json({ success: false, error: 'Serial required' });
  if (!localPath || !remotePath) return res.status(400).json({ success: false, error: 'Path required' });

  exec(`"${ADB_PATH}" -s "${serial}" push "${localPath}" "${remotePath}"`, { timeout: 60000 }, (err, stdout, stderr) => {
    if (err) return res.json({ success: false, error: stderr || err.message });
    res.json({ success: true, output: stdout.trim() });
  });
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

// 获取设备实时性能
app.post('/api/tvlive/device-perf', (req, res) => {
  const { serial } = req.body;
  if (!serial) return res.status(400).json({ success: false, error: 'Serial required' });

  const commands = [
    // CPU 使用率
    `"${ADB_PATH}" -s "${serial}" shell top -n 1 | findstr /C:"TOTAL"`,
    // 内存信息
    `"${ADB_PATH}" -s "${serial}" shell dumpsys meminfo ${TV_LIVE_PACKAGE}`,
    // 电池温度
    `"${ADB_PATH}" -s "${serial}" shell dumpsys battery | findstr temperature`,
    // 帧率
    `"${ADB_PATH}" -s "${serial}" shell dumpsys SurfaceFlinger --latency 1>/dev/null 2>&1 || echo "FPS测量需要root权限"`
  ];

  let result = {
    cpuUsage: 0,
    memTotal: 0,
    memUsed: 0,
    batteryTemp: 0,
    fps: 0,
    timestamp: Date.now()
  };

  // 并行执行命令
  Promise.all([
    new Promise(resolve => {
      exec(commands[0], { timeout: 3000 }, (err, stdout) => {
        if (stdout) {
          // 解析 CPU 使用率
          const match = stdout.match(/TOTAL\s+\d+%/);
          if (match) {
            result.cpuUsage = parseInt(match[0].match(/(\d+)/)[1]);
          }
        }
        resolve();
      });
    }),
    new Promise(resolve => {
      exec(commands[1], { timeout: 3000 }, (err, stdout) => {
        if (stdout) {
          // 解析内存信息
          const totalMatch = stdout.match(/TOTAL\s+(\d+)/);
          if (totalMatch) {
            result.memUsed = parseInt(totalMatch[1]) / 1024; // MB
          }
        }
        resolve();
      });
    }),
    new Promise(resolve => {
      exec(commands[2], { timeout: 3000 }, (err, stdout) => {
        if (stdout) {
          // 解析电池温度
          const match = stdout.match(/temperature:\s*(\d+)/);
          if (match) {
            result.batteryTemp = parseInt(match[1]) / 10; // 摄氏度
          }
        }
        resolve();
      });
    }),
    new Promise(resolve => {
      // 从日志中计算FPS
      const recentPlaybackLogs = logs.filter(l => {
        const type = l.logType || l.type;
        return type === 'playback' || type === '播放';
      });
      
      if (recentPlaybackLogs.length >= 2) {
        const times = recentPlaybackLogs.map(l => l.timestamp || l.serverTime || 0).sort((a, b) => a - b);
        const diff = times[times.length - 1] - times[0];
        if (diff > 0) {
          result.fps = Math.round((recentPlaybackLogs.length - 1) / (diff / 1000));
        }
      }
      resolve();
    })
  ]).then(() => {
    // 获取总内存信息
    exec(`"${ADB_PATH}" -s "${serial}" shell cat /proc/meminfo | findstr MemTotal`, (err, stdout) => {
      if (stdout) {
        const match = stdout.match(/(\d+)/);
        if (match) {
          result.memTotal = parseInt(match[1]) / 1024; // MB
        }
      }
      res.json({ success: true, data: result });
    });
  });
});

// ========== 虎牙 API 监控端点 ==========

// 虎牙 XOR 解码辅助
const HUYA_XOR_KEY = 90;
function decodeHuyaString(encoded) {
  let result = '';
  for (let i = 0; i < encoded.length; i++) {
    result += String.fromCharCode(encoded.charCodeAt(i) ^ HUYA_XOR_KEY);
  }
  return result;
}

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

// 虎牙凭证（解码后的真实值）
const HUYA_CREDENTIALS = {
  gameId: 2426 ^ HUYA_XOR_KEY,
  appId: decodeHuyaString('khinol'),
  appKey: decodeHuyaString('>b<kci>>')
};

// 获取虎牙凭证信息
app.get('/api/huya/credentials', (req, res) => {
  res.json({
    success: true,
    credentials: {
      gameId: HUYA_CREDENTIALS.gameId,
      appId: HUYA_CREDENTIALS.appId,
      appKey: HUYA_CREDENTIALS.appKey.substring(0, 2) + '****' + HUYA_CREDENTIALS.appKey.substring(HUYA_CREDENTIALS.appKey.length - 2),
      decoded: true
    },
    raw: {
      gameId: HUYA_CREDENTIALS.gameId,
      appId: HUYA_CREDENTIALS.appId,
      appKey: HUYA_CREDENTIALS.appKey
    }
  });
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

  const exportData = {
    exportedAt: new Date().toISOString(),
    totalLogs: filtered.length,
    logs: filtered
  };

  res.setHeader('Content-Type', 'application/json');
  res.setHeader('Content-Disposition', `attachment; filename=logs_${Date.now()}.json`);
  res.send(JSON.stringify(exportData, null, 2));
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
