/**
 * TV Live 云端日志服务器 - Cloudflare Workers 版本
 * 
 * 免费额度：
 * - 每天 100,000 次请求
 * - 30 个 Workers
 * - KV 存储 1GB
 * 
 * 部署步骤：
 * 1. 安装 wrangler: npm install -g wrangler
 * 2. 登录: wrangler login
 * 3. 创建 KV: wrangler kv:create --binding=LOG_STORE
 * 4. 部署: wrangler deploy
 */

// 环境变量（在 wrangler.toml 中配置）
// LOG_STORE - KV 存储绑定

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    const kv = env.LOG_STORE;

    // CORS 头
    const corsHeaders = {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET, POST, DELETE, OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type, x-api-key',
      'Content-Type': 'application/json'
    };

    // OPTIONS 预检请求
    if (request.method === 'OPTIONS') {
      return new Response(null, { headers: corsHeaders });
    }

    try {
      // 路由处理
      const path = url.pathname.replace('/api', '');

      // 健康检查
      if (path === '/status' || path === '/') {
        return handleStatus(kv, corsHeaders);
      }

      // 设备注册
      if (path === '/device/register' && request.method === 'POST') {
        return handleDeviceRegister(request, kv, corsHeaders);
      }

      // 获取设备列表
      if (path === '/devices' && request.method === 'GET') {
        return handleGetDevices(kv, corsHeaders);
      }

      // 发送日志
      if (path.startsWith('/logs/') && request.method === 'POST') {
        const deviceId = path.replace('/logs/', '');
        return handleSendLogs(request, kv, deviceId, corsHeaders);
      }

      // 获取所有日志
      if (path === '/logs' && request.method === 'GET') {
        const deviceId = url.searchParams.get('deviceId');
        const type = url.searchParams.get('type');
        const limit = parseInt(url.searchParams.get('limit')) || 200;
        return handleGetLogs(kv, deviceId, type, limit, corsHeaders);
      }

      // 获取指定设备日志
      if (path.startsWith('/logs/') && request.method === 'GET') {
        const deviceId = path.replace('/logs/', '');
        const limit = parseInt(url.searchParams.get('limit')) || 500;
        const type = url.searchParams.get('type');
        return handleGetDeviceLogs(kv, deviceId, limit, type, corsHeaders);
      }

      // 清空所有日志
      if (path === '/logs' && request.method === 'DELETE') {
        return handleClearAllLogs(kv, corsHeaders);
      }

      // 清空指定设备日志
      if (path.startsWith('/logs/') && request.method === 'DELETE') {
        const deviceId = path.replace('/logs/', '');
        return handleClearDeviceLogs(kv, deviceId, corsHeaders);
      }

      // SSE 实时日志推送
      if (path === '/stream' && request.method === 'GET') {
        return handleSSEStream(request, kv, corsHeaders);
      }

      // 404
      return new Response(JSON.stringify({ error: 'Not found' }), {
        status: 404,
        headers: corsHeaders
      });

    } catch (error) {
      console.error('Worker error:', error);
      return new Response(JSON.stringify({ error: error.message }), {
        status: 500,
        headers: corsHeaders
      });
    }
  }
};

// 健康检查
async function handleStatus(kv, corsHeaders) {
  let deviceCount = 0;
  let totalLogs = 0;

  // 遍历所有设备
  const devices = await kv.list({ prefix: 'device:' });
  deviceCount = devices.keys.length;

  // 统计日志数量
  for (const key of devices.keys) {
    const logs = await kv.get(`logs:${key.name.replace('device:', '')}`);
    if (logs) {
      try {
        const parsed = JSON.parse(logs);
        totalLogs += parsed.length;
      } catch {}
    }
  }

  return new Response(JSON.stringify({
    status: 'ok',
    uptime: Date.now(),
    registeredDevices: deviceCount,
    totalLogs,
    serverTime: new Date().toISOString(),
    version: '1.0.0-workers'
  }), { headers: corsHeaders });
}

// 设备注册
async function handleDeviceRegister(request, kv, corsHeaders) {
  const body = await request.json();
  const { deviceId, deviceName, deviceModel, appVersion, brand, manufacturer } = body;

  if (!deviceId) {
    return new Response(JSON.stringify({ error: 'deviceId required' }), {
      status: 400,
      headers: corsHeaders
    });
  }

  const deviceInfo = {
    deviceId,
    deviceName: deviceName || 'Unknown',
    deviceModel: deviceModel || 'Unknown',
    appVersion: appVersion || 'Unknown',
    brand: brand || '',
    manufacturer: manufacturer || '',
    registeredAt: new Date().toISOString(),
    lastActive: Date.now(),
    logCount: 0
  };

  await kv.put(`device:${deviceId}`, JSON.stringify(deviceInfo));

  return new Response(JSON.stringify({
    success: true,
    deviceId,
    message: 'Device registered successfully'
  }), { headers: corsHeaders });
}

// 获取设备列表
async function handleGetDevices(kv, corsHeaders) {
  const now = Date.now();
  const devices = [];

  const keys = await kv.list({ prefix: 'device:' });
  for (const key of keys.keys) {
    const deviceData = await kv.get(key.name);
    if (deviceData) {
      try {
        const device = JSON.parse(deviceData);
        // 只返回最近24小时活跃的设备
        if (now - device.lastActive < 24 * 3600 * 1000) {
          const logsData = await kv.get(`logs:${device.deviceId}`);
          const bufferedLogs = logsData ? JSON.parse(logsData).length : 0;
          devices.push({
            ...device,
            bufferedLogs
          });
        }
      } catch {}
    }
  }

  return new Response(JSON.stringify({
    devices,
    count: devices.length
  }), { headers: corsHeaders });
}

// 发送日志
async function handleSendLogs(request, kv, deviceId, corsHeaders) {
  const body = await request.json();
  const logs = body.logs || [body];

  if (!Array.isArray(logs)) {
    return new Response(JSON.stringify({ error: 'logs must be an array' }), {
      status: 400,
      headers: corsHeaders
    });
  }

  // 获取现有日志
  const existingLogs = await kv.get(`logs:${deviceId}`);
  let deviceLogs = existingLogs ? JSON.parse(existingLogs) : [];

  const newLogs = [];
  for (const log of logs) {
    const logEntry = {
      ...log,
      deviceId,
      serverTime: new Date().toISOString(),
      receivedAt: Date.now()
    };

    deviceLogs.unshift(logEntry);
    newLogs.push(logEntry);

    // 限制日志数量
    if (deviceLogs.length > 10000) {
      deviceLogs = deviceLogs.slice(0, 10000);
    }
  }

  // 存储日志
  await kv.put(`logs:${deviceId}`, JSON.stringify(deviceLogs));

  // 更新设备活跃时间
  const deviceData = await kv.get(`device:${deviceId}`);
  if (deviceData) {
    try {
      const device = JSON.parse(deviceData);
      device.lastActive = Date.now();
      device.logCount += newLogs.length;
      await kv.put(`device:${deviceId}`, JSON.stringify(device));
    } catch {}
  }

  return new Response(JSON.stringify({
    success: true,
    received: newLogs.length
  }), { headers: corsHeaders });
}

// 获取所有日志
async function handleGetLogs(kv, deviceId, type, limit, corsHeaders) {
  let allLogs = [];

  if (deviceId) {
    const logsData = await kv.get(`logs:${deviceId}`);
    if (logsData) {
      allLogs = JSON.parse(logsData);
    }
  } else {
    // 获取所有设备的日志
    const keys = await kv.list({ prefix: 'logs:' });
    for (const key of keys.keys) {
      const logsData = await kv.get(key.name);
      if (logsData) {
        const logs = JSON.parse(logsData);
        allLogs = allLogs.concat(logs.slice(-100));
      }
    }
  }

  // 按类型过滤
  if (type) {
    allLogs = allLogs.filter(l => (l.logType || l.type) === type);
  }

  // 按时间排序
  allLogs.sort((a, b) => (b.timestamp || 0) - (a.timestamp || 0));

  return new Response(JSON.stringify({
    logs: allLogs.slice(0, limit),
    total: allLogs.length
  }), { headers: corsHeaders });
}

// 获取指定设备日志
async function handleGetDeviceLogs(kv, deviceId, limit, type, corsHeaders) {
  const logsData = await kv.get(`logs:${deviceId}`);
  let logs = logsData ? JSON.parse(logsData) : [];

  // 按类型过滤
  if (type) {
    logs = logs.filter(l => (l.logType || l.type) === type);
  }

  const total = logs.length;

  return new Response(JSON.stringify({
    logs: logs.slice(0, limit),
    total,
    deviceId
  }), { headers: corsHeaders });
}

// 清空所有日志
async function handleClearAllLogs(kv, corsHeaders) {
  let totalCleared = 0;

  const keys = await kv.list({ prefix: 'logs:' });
  for (const key of keys.keys) {
    const logsData = await kv.get(key.name);
    if (logsData) {
      try {
        totalCleared += JSON.parse(logsData).length;
      } catch {}
    }
    await kv.delete(key.name);
  }

  return new Response(JSON.stringify({
    success: true,
    totalCleared
  }), { headers: corsHeaders });
}

// 清空指定设备日志
async function handleClearDeviceLogs(kv, deviceId, corsHeaders) {
  const logsData = await kv.get(`logs:${deviceId}`);
  let cleared = 0;

  if (logsData) {
    try {
      cleared = JSON.parse(logsData).length;
    } catch {}
    await kv.delete(`logs:${deviceId}`);
  }

  return new Response(JSON.stringify({
    success: true,
    cleared
  }), { headers: corsHeaders });
}

// SSE 实时日志流（替代 WebSocket）
async function handleSSEStream(request, kv, corsHeaders) {
  const deviceId = new URL(request.url).searchParams.get('deviceId');

  const responseHeaders = {
    'Content-Type': 'text/event-stream',
    'Cache-Control': 'no-cache',
    'Connection': 'keep-alive',
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'GET, OPTIONS'
  };

  // 如果指定了设备，发送该设备的历史日志
  if (deviceId) {
    const logsData = await kv.get(`logs:${deviceId}`);
    if (logsData) {
      const logs = JSON.parse(logsData).slice(-500);
      return new Response(`data: ${JSON.stringify({ type: 'history_logs', deviceId, logs })}\n\n`, {
        headers: responseHeaders
      });
    }
  }

  // 返回所有设备的最新日志
  let allLogs = [];
  const keys = await kv.list({ prefix: 'logs:' });
  for (const key of keys.keys) {
    const logsData = await kv.get(key.name);
    if (logsData) {
      const logs = JSON.parse(logsData);
      allLogs = allLogs.concat(logs.slice(-50));
    }
  }

  allLogs.sort((a, b) => (b.timestamp || 0) - (a.timestamp || 0));

  const message = {
    type: 'history_logs',
    logs: allLogs.slice(0, 200)
  };

  return new Response(`data: ${JSON.stringify(message)}\n\n`, {
    headers: responseHeaders
  });
}
