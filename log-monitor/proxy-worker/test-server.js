/**
 * 本地代理测试服务器
 * 
 * 用途：
 * 1. 测试 Vercel 源站是否可以访问
 * 2. 模拟 Cloudflare Worker 代理逻辑
 * 3. 验证 API 接口正常工作
 */

const http = require('http');
const https = require('https');
const { URL } = require('url');

// 配置
const CONFIG = {
  PORT: 8787,
  VERCEL_BASE_URL: 'https://tv-live-cloud-log-server.vercel.app',
  TIMEOUT: 10000,
  CACHE_TTL: 3600 // 静态资源缓存1小时
};

// 静态资源扩展名
const STATIC_EXTENSIONS = ['.css', '.js', '.png', '.jpg', '.jpeg', '.gif', '.svg', '.ico', '.woff', '.woff2', '.ttf'];

// 简单缓存
const cache = new Map();

// CORS 头
const CORS_HEADERS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET, POST, PUT, DELETE, OPTIONS',
  'Access-Control-Allow-Headers': 'Content-Type, x-api-key, Authorization',
  'Access-Control-Max-Age': '86400'
};

// 创建服务器
const server = http.createServer(async (req, res) => {
  const startTime = Date.now();
  
  try {
    // OPTIONS 预检请求
    if (req.method === 'OPTIONS') {
      res.writeHead(204, CORS_HEADERS);
      res.end();
      return;
    }

    const url = new URL(req.url, `http://${req.headers.host}`);
    
    console.log(`[${new Date().toISOString()}] ${req.method} ${req.url}`);

    // 状态检查接口
    if (url.pathname === '/' || url.pathname === '/status') {
      await handleStatus(res, req);
      return;
    }

    // 检查缓存
    const cacheKey = req.url;
    const isStatic = checkIsStaticResource(url.pathname);
    
    if (isStatic && cache.has(cacheKey)) {
      const cached = cache.get(cacheKey);
      const now = Date.now();
      
      if (now - cached.time < CONFIG.CACHE_TTL * 1000) {
        console.log(`  ✅ 缓存命中: ${req.url}`);
        res.writeHead(200, {
          ...cached.headers,
          'X-Cache': 'HIT',
          ...CORS_HEADERS
        });
        res.end(cached.body);
        return;
      } else {
        cache.delete(cacheKey);
      }
    }

    // 转发请求到 Vercel
    console.log(`  🔄 转发到: ${CONFIG.VERCEL_BASE_URL}${req.url}`);
    const proxyResponse = await proxyRequest(req);
    
    // 处理响应
    const responseHeaders = {
      ...proxyResponse.headers,
      'X-Cache': isStatic ? 'BYPASS' : 'SKIP',
      'X-Proxy-By': 'Local-Test-Proxy',
      'X-Response-Time': `${Date.now() - startTime}ms`,
      ...CORS_HEADERS
    };

    // 缓存静态资源
    if (isStatic) {
      const body = proxyResponse.body;
      cache.set(cacheKey, {
        body,
        headers: responseHeaders,
        time: Date.now()
      });
      console.log(`  💾 已缓存: ${req.url}`);
    }

    res.writeHead(proxyResponse.statusCode, responseHeaders);
    res.end(proxyResponse.body);

  } catch (error) {
    console.error(`  ❌ 错误: ${error.message}`);
    res.writeHead(502, {
      ...CORS_HEADERS,
      'Content-Type': 'application/json'
    });
    res.end(JSON.stringify({
      error: '代理请求失败',
      message: error.message,
      timestamp: new Date().toISOString(),
      tips: '可能是 Vercel 源站暂时不可用，请稍后重试'
    }));
  }
});

// 处理状态检查
async function handleStatus(res, req) {
  let backendStatus = { status: 'unknown' };
  
  try {
    const testUrl = new URL('/api/status', CONFIG.VERCEL_BASE_URL);
    const response = await fetch(testUrl.toString(), {
      method: 'GET',
      headers: {
        'User-Agent': 'TV-Live-Proxy-Test/1.0'
      }
    });
    
    backendStatus = await response.json();
  } catch (e) {
    backendStatus = { 
      status: 'unreachable', 
      error: e.message 
    };
  }

  const statusResponse = {
    status: 'ok',
    proxy: {
      type: 'local-test',
      version: '1.0.0',
      uptime: process.uptime(),
      cacheSize: cache.size
    },
    backend: backendStatus,
    message: 'TV Live 本地代理测试服务器',
    testTime: new Date().toISOString(),
    endpoints: {
      测试代理: 'http://localhost:' + CONFIG.PORT + '/',
      API示例: 'http://localhost:' + CONFIG.PORT + '/api/logs',
      源站: CONFIG.VERCEL_BASE_URL
    }
  };

  res.writeHead(200, {
    'Content-Type': 'application/json',
    ...CORS_HEADERS
  });
  res.end(JSON.stringify(statusResponse, null, 2));
}

// 转发请求
function proxyRequest(req) {
  return new Promise((resolve, reject) => {
    const targetUrl = new URL(req.url, CONFIG.VERCEL_BASE_URL);
    
    const options = {
      hostname: targetUrl.hostname,
      port: 443,
      path: targetUrl.pathname + targetUrl.search,
      method: req.method,
      headers: {
        ...req.headers,
        'host': targetUrl.hostname,
        'x-forwarded-host': targetUrl.hostname,
        'x-proxy-by': 'TV-Live-Local-Test'
      },
      timeout: CONFIG.TIMEOUT
    };

    const proxyReq = https.request(options, (proxyRes) => {
      const chunks = [];
      
      proxyRes.on('data', (chunk) => chunks.push(chunk));
      proxyRes.on('end', () => {
        resolve({
          statusCode: proxyRes.statusCode,
          headers: proxyRes.headers,
          body: Buffer.concat(chunks)
        });
      });
    });

    proxyReq.on('error', reject);
    proxyReq.on('timeout', () => {
      proxyReq.destroy();
      reject(new Error('请求超时'));
    });

    // 处理请求体
    if (req.method !== 'GET' && req.method !== 'DELETE') {
      req.pipe(proxyReq);
    } else {
      proxyReq.end();
    }
  });
}

// 检查是否是静态资源
function checkIsStaticResource(pathname) {
  return STATIC_EXTENSIONS.some(ext => pathname.endsWith(ext));
}

// 启动服务器
server.listen(CONFIG.PORT, () => {
  console.log('='.repeat(60));
  console.log('🚀 TV Live 本地代理测试服务器已启动');
  console.log('='.repeat(60));
  console.log('');
  console.log(`📡 代理地址: http://localhost:${CONFIG.PORT}`);
  console.log(`🎯 源站地址: ${CONFIG.VERCEL_BASE_URL}`);
  console.log(`⏱️  静态缓存: ${CONFIG.CACHE_TTL}秒`);
  console.log('');
  console.log('📖 使用方法:');
  console.log(`   状态检查: http://localhost:${CONFIG.PORT}/`);
  console.log(`   API测试:  http://localhost:${CONFIG.PORT}/api/logs`);
  console.log('');
  console.log('💡 提示:');
  console.log('   - 此服务器用于本地测试 Vercel 连通性');
  console.log('   - 如果源站无法访问，会显示错误信息');
  console.log('   - 正式部署请使用 Cloudflare Workers');
  console.log('');
  console.log('='.repeat(60));
});
