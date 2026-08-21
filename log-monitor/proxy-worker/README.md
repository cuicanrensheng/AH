# TV Live 日志服务器 - Cloudflare 反向代理

## 📋 目录

1. [功能介绍](#功能介绍)
2. [部署前准备](#部署前准备)
3. [3 步快速部署](#3-步快速部署)
4. [测试验证](#测试验证)
5. [使用方法](#使用方法)
6. [常见问题](#常见问题)

---

## 功能介绍

### 为什么需要反向代理？

Vercel 使用 Google Cloud 基础设施，在中国大陆访问速度很慢甚至无法访问。

本项目通过 Cloudflare Workers 反向代理解决此问题：

```
用户请求 → Cloudflare Worker（国内节点：上海/广州/北京）→ Vercel（源站）→ 返回结果
```

### Cloudflare 节点分布

Cloudflare 在中国大陆有多个节点：
- 🇨🇳 上海
- 🇨🇳 广州  
- 🇨🇳 北京
- 🇨🇳 香港
- 🌏 新加坡
- 🌏 日本

### 免费额度

Cloudflare Workers 免费版足够个人使用：
- 每天 **100,000 次**请求
- **30 个**Workers
- 自动缓存静态资源

### 主要功能

- ✅ 反向代理 Vercel 所有 API
- ✅ 静态资源自动缓存（CSS/JS/图片）
- ✅ CORS 跨域支持
- ✅ 请求头转发（保留真实客户端 IP）
- ✅ 错误处理和状态检查
- ✅ 支持 SSE 流式响应

---

## 部署前准备

### 步骤 1：注册 Cloudflare 账号

1. 访问 https://dash.cloudflare.com/sign-up
2. 填写邮箱和密码
3. 验证邮箱

### 步骤 2（可选）：安装 Wrangler CLI

如果您希望用命令行部署：

```bash
# 安装 Node.js（如果没有）
# https://nodejs.org/zh-cn/download

# 安装 wrangler
npm install -g wrangler

# 登录 Cloudflare
wrangler login
```

**注意**：本项目推荐直接在 Cloudflare 控制台部署，无需 CLI。

---

## 3 步快速部署

### 方法一：控制台部署（推荐新手）

#### 步骤 1：创建 Worker

1. 登录 https://dash.cloudflare.com
2. 左侧菜单点击「**Workers & Pages**」
3. 点击「**Create application**」
4. 选择「**Create Worker**」
5. 命名为 `tv-live-proxy`
6. 点击「**Deploy**」

#### 步骤 2：粘贴代码

1. 进入刚创建的 Worker
2. 点击「**Code**」标签
3. 清空默认代码
4. 打开本地文件 `d:\ASDF\TV Live\AH-main\log-monitor\proxy-worker\index.js`
5. 复制所有代码
6. 粘贴到 Cloudflare 编辑器
7. 点击「**Save and Deploy**」

#### 步骤 3：获取域名

部署成功后会显示您的 Worker 域名：
```
https://tv-live-proxy.你的用户名.workers.dev
```

**部署完成！** 🎉

---

### 方法二：命令行部署

#### 步骤 1：进入目录

```bash
cd d:\ASDF\TV Live\AH-main\log-monitor\proxy-worker
```

#### 步骤 2：安装依赖

```bash
npm install
```

#### 步骤 3：部署

```bash
wrangler deploy
```

部署成功后会显示域名。

---

## 测试验证

### 测试 1：代理状态检查

```bash
# 访问代理状态接口
curl https://tv-live-proxy.你的用户名.workers.dev/
```

应该返回类似：
```json
{
  "status": "ok",
  "proxy": {
    "type": "cloudflare-workers",
    "region": "中国节点",
    "cache": "enabled",
    "version": "1.0.0"
  },
  "backend": {
    "status": "ok",
    "registeredDevices": 0,
    "totalLogs": 0,
    "version": "1.0.0-workers"
  }
}
```

### 测试 2：代理 API

```bash
# 测试日志接口
curl https://tv-live-proxy.你的用户名.workers.dev/api/logs

# 测试设备接口
curl https://tv-live-proxy.你的用户名.workers.dev/api/devices
```

---

## 使用方法

### 手机端 App 配置

```java
// 原来直接连接 Vercel（大陆无法访问）
// String serverUrl = "https://tv-live-cloud-log-server.vercel.app";

// 现在连接 Cloudflare 代理（大陆快速访问）
String serverUrl = "https://tv-live-proxy.你的用户名.workers.dev";

CloudLogSender sender = CloudLogSender.getInstance(this);
sender.setServerUrl(serverUrl);
sender.setEnabled(true);
sender.start();
```

### 电脑端监控工具配置

在「☁️ 云端」标签中：
1. 输入代理地址：`https://tv-live-proxy.你的用户名.workers.dev`
2. 点击「连接云端服务器」
3. 即可正常查看日志

### API 接口列表

所有 Vercel API 都可以通过代理访问：

| Vercel API | 代理 API |
|------------|----------|
| `https://tv-live-cloud-log-server.vercel.app/api/status` | `https://tv-live-proxy.xxx.workers.dev/api/status` |
| `.../api/devices` | `.../api/devices` |
| `.../api/logs` | `.../api/logs` |
| `.../api/logs/:deviceId` | `.../api/logs/:deviceId` |

---

## 常见问题

### Q1：部署后访问报错 502？

**可能原因**：Vercel 源站暂时不可用

**排查步骤**：
1. 直接访问 Vercel：https://tv-live-cloud-log-server.vercel.app/api/status
2. 如果 Vercel 正常，检查代理 Worker 代码
3. 在 Cloudflare 控制台查看 Worker 日志

### Q2：页面加载慢？

**原因**：首次请求需要从 Vercel 拉取数据，后续会走 Cloudflare 缓存

**优化**：
- 静态资源（CSS/JS/图片）会自动缓存 1 小时
- API 请求不缓存（实时性要求）

### Q3：如何绑定自定义域名？

1. 在 Cloudflare 控制台点击 Worker →「**Triggers**」
2. 点击「**Custom Domains**」→「**Add Custom Domain**」
3. 输入子域名（如 `log.yourdomain.com`）
4. 配置 DNS 记录
5. 等待生效

### Q4：免费额度够用吗？

对于个人使用完全足够：
- 每天 10 万次请求 = 平均每秒 1.1 次
- 如果不够，可以升级到付费版（$5/月）

### Q5：如何查看 Worker 日志？

Cloudflare 控制台 → Worker →「**Logs**」标签，可以查看实时日志。

### Q6：支持 WebSocket 吗？

本项目是 HTTP API 方式，不使用 WebSocket。如果需要 WebSocket，Vercel 源站支持，代理也能正常转发。

### Q7：如何更新代码？

修改 `index.js` 后：
- 控制台部署：直接在编辑器保存即可
- CLI 部署：执行 `wrangler deploy`

---

## 架构图

```
┌─────────────┐     ┌─────────────────────┐     ┌─────────────────┐
│  手机 App    │────▶│ Cloudflare Worker  │────▶│    Vercel 源站    │
│  电脑监控    │     │  (中国节点)         │     │  (Google Cloud)  │
└─────────────┘     └─────────────────────┘     └─────────────────┘
                            │
                     ┌──────┴──────┐
                     │  静态资源缓存  │
                     └─────────────┘
```

## 版本历史

- **v1.0.0** (2024-01-01)
  - 初始版本
  - 支持 Vercel 反向代理
  - 静态资源自动缓存
  - CORS 跨域支持
  - 错误处理
