/**
 * TV Live 日志服务器 - Cloudflare 反向代理
 * 
 * 功能：
 * - 反向代理 Vercel 服务到 Cloudflare
 * - 自动缓存静态资源
 * - 支持 WebSocket 升级
 * - 添加 CORS 跨域支持
 * - 支持 HTTPS 强制跳转
 * 
 * 免费额度：
 * - 每天 100,000 次请求
 * - 30 个 Workers
 */

const VERCEL_BASE_URL = 'https://tv-live-cloud-log-server.vercel.app';

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    
    // CORS 头
    const corsHeaders = {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET, POST, PUT, DELETE, OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type, x-api-key, Authorization',
      'Access-Control-Max-Age': '86400'
    };

    // OPTIONS 预检请求
    if (request.method === 'OPTIONS') {
      return new Response(null, { headers: corsHeaders });
    }

    try {
      // 构造目标 URL
      const targetUrl = new URL(url.pathname + url.search, VERCEL_BASE_URL);
      
      // 检查是否是静态资源（可以缓存）
      const isStaticResource = checkIsStaticResource(url.pathname);
      const isAPI = url.pathname.startsWith('/api/');
      
      // 如果是根路径，返回代理状态信息
      if (url.pathname === '/' || url.pathname === '/status') {
        return handleProxyStatus(request, corsHeaders);
      }

      // 转发请求到 Vercel
      const response = await fetch(targetUrl.toString(), {
        headers: getProxyHeaders(request, url.hostname),
        method: request.method,
        body: request.body,
        cf: {
          // 缓存配置
          cacheEverything: isStaticResource,
          cacheTtl: isStaticResource ? 3600 : 0,  // 静态缓存1小时
          minify: {
            javascript: true,
            css: true,
            html: true
          },
          // 中国大陆优化
          timezone: 'Asia/Shanghai'
        }
      });

      // 处理响应
      return createProxyResponse(response, corsHeaders, isStaticResource);

    } catch (error) {
      console.error('Proxy error:', error);
      
      // 返回友好的错误信息
      return new Response(JSON.stringify({
        error: '代理请求失败',
        message: error.message,
        timestamp: new Date().toISOString(),
        tips: '如持续出现此问题，可能是 Vercel 源站暂时不可用'
      }), {
        status: 502,
        headers: {
          ...corsHeaders,
          'Content-Type': 'application/json'
        }
      });
    }
  }
};

// 检查是否是静态资源
function checkIsStaticResource(pathname) {
  const staticExtensions = ['.css', '.js', '.png', '.jpg', '.jpeg', '.gif', '.svg', '.ico', '.woff', '.woff2', '.ttf'];
  return staticExtensions.some(ext => pathname.endsWith(ext));
}

// 获取代理请求头
function getProxyHeaders(request, proxyHost) {
  const headers = new Headers(request.headers);
  
  // 设置真实客户端 IP
  const cfConnectingIP = request.headers.get('cf-connecting-ip');
  if (cfConnectingIP) {
    headers.set('x-forwarded-for', cfConnectingIP);
  }
  
  // 保持原始 Host
  headers.set('host', 'tv-live-cloud-log-server.vercel.app');
  headers.set('x-forwarded-host', 'tv-live-cloud-log-server.vercel.app');
  headers.set('x-original-host', proxyHost);
  headers.set('x-proxy-by', 'tv-live-proxy-cf');
  
  return headers;
}

// 创建代理响应
function createProxyResponse(originalResponse, corsHeaders, isCached) {
  const headers = new Headers(originalResponse.headers);
  
  // 添加 CORS 头
  Object.entries(corsHeaders).forEach(([key, value]) => {
    headers.set(key, value);
  });
  
  // 添加代理相关头
  headers.set('X-Proxy-By', 'Cloudflare-Workers');
  headers.set('X-Cache', isCached ? 'HIT' : 'BYPASS');
  headers.set('X-Forward-To', 'tv-live-cloud-log-server.vercel.app');
  
  // 移除 Vercel 响应头中可能有问题的头
  headers.delete('x-vercel-cache');
  headers.delete('x-vercel-id');
  
  // 如果是 SSE 流，保持连接
  const contentType = headers.get('content-type') || '';
  if (contentType.includes('text/event-stream')) {
    headers.set('Cache-Control', 'no-cache, no-transform');
    headers.set('Connection', 'keep-alive');
  }
  
  return new Response(originalResponse.body, {
    status: originalResponse.status,
    statusText: originalResponse.statusText,
    headers
  });
}

// 代理状态检查
async function handleProxyStatus(request, corsHeaders) {
  try {
    // 尝试获取后端状态
    const backendUrl = new URL('/api/status', VERCEL_BASE_URL);
    const backendResponse = await fetch(backendUrl.toString());
    
    const backendData = await backendResponse.json();
    
    return new Response(JSON.stringify({
      status: 'ok',
      proxy: {
        type: 'cloudflare-workers',
        region: '中国节点',
        cache: 'enabled',
        version: '1.0.0'
      },
      backend: backendData,
      message: 'TV Live 日志服务器 - 反向代理运行中',
      endpoints: {
        代理地址: '当前 Worker 地址',
        源站地址: 'https://tv-live-cloud-log-server.vercel.app',
        日志接口: '/api/logs',
        设备接口: '/api/devices',
        状态接口: '/api/status'
      }
    }), {
      headers: {
        ...corsHeaders,
        'Content-Type': 'application/json'
      }
    });
  } catch (error) {
    return new Response(JSON.stringify({
      status: 'degraded',
      proxy: '运行正常',
      backend: '不可达',
      error: error.message,
      message: '代理正常，但 Vercel 源站暂时不可用'
    }), {
      status: 200,
      headers: {
        ...corsHeaders,
        'Content-Type': 'application/json'
      }
    });
  }
}
