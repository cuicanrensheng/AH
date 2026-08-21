# TV Live 云端日志服务器 - Cloudflare Workers 部署指南

## 📋 目录

1. [Cloudflare 账号准备](#cloudflare-账号准备)
2. [安装部署工具](#安装部署工具)
3. [部署 Workers](#部署-workers)
4. [配置手机端](#配置手机端)
5. [电脑端连接](#电脑端连接)
6. [API 接口说明](#api-接口说明)
7. [常见问题](#常见问题)

---

## Cloudflare 账号准备

### 步骤 1：注册 Cloudflare 账号

1. 访问 https://dash.cloudflare.com/sign-up
2. 填写邮箱和密码注册账号
3. 确认邮箱验证

### 步骤 2（可选）：绑定自定义域名

1. 在 Cloudflare 控制台点击「Add a domain」
2. 输入您的域名
3. 根据提示在域名服务商处修改 DNS 记录
4. 等待 DNS 生效（通常几分钟）

---

## 安装部署工具

### 步骤 1：安装 Node.js

下载地址：https://nodejs.org/zh-cn/download

选择 **LTS 版本**（长期支持版本）

### 步骤 2：安装 Wrangler CLI

```bash
npm install -g wrangler
```

### 步骤 3：登录 Cloudflare

```bash
wrangler login
```

会自动打开浏览器，在页面上点击「Allow」授权。

---

## 部署 Workers

### 步骤 1：进入项目目录

```bash
cd d:\ASDF\TV Live\AH-main\log-monitor\workers
```

### 步骤 2：创建 KV 存储

```bash
wrangler kv create --binding=LOG_STORE
```

执行后会返回一个 KV ID，复制它。

### 步骤 3：更新配置文件

编辑 `wrangler.toml` 文件，将 `REPLACE_WITH_YOUR_KV_ID` 替换为上一步获得的 KV ID：

```toml
kv_bindings = [
  { binding = "LOG_STORE", id = "你的KV_ID" }
]
```

### 步骤 4：部署

```bash
wrangler deploy
```

部署成功后会显示您的 Workers 域名，类似：
```
⚡️ Deploying with Wrangler...
  📦 Uploaded to Cloudflare's network
  🚧 Deploy to production:
    https://tv-live-cloud-log-server.your-subdomain.workers.dev
```

### 步骤 5：测试服务

```bash
curl https://tv-live-cloud-log-server.your-subdomain.workers.dev/api/status
```

应该返回类似：
```json
{
  "status": "ok",
  "registeredDevices": 0,
  "totalLogs": 0,
  "serverTime": "2024-01-01T00:00:00.000Z",
  "version": "1.0.0-workers"
}
```

### 步骤 6（可选）：绑定自定义域名

如果您有自定义域名：

1. 登录 Cloudflare 控制台
2. 进入 Workers → 选择您的 Worker
3. 点击「Triggers」→「Custom Domains」
4. 点击「Add Custom Domain」
5. 输入子域名（如 `log.yourdomain.com`）
6. 等待 DNS 生效

---

## 配置手机端

### 在 App 中配置云端服务器地址

在 `CloudLogSender` 中设置：

```java
// 获取 CloudLogSender 实例
CloudLogSender sender = CloudLogSender.getInstance(context);

// 设置 Workers 地址
sender.setServerUrl("https://tv-live-cloud-log-server.your-subdomain.workers.dev");

// 如果设置了 API_KEY，需要在这里设置
// sender.setApiKey("your-api-key");

// 启用云端日志
sender.setEnabled(true);
sender.start();
```

### 在设置页面动态配置

```java
// SharedPreferences 中存储服务器地址
SharedPreferences prefs = getSharedPreferences("cloud_log_config", MODE_PRIVATE);
String serverUrl = prefs.getString("server_url", "");

CloudLogSender sender = CloudLogSender.getInstance(context);
sender.setServerUrl(serverUrl);
if (sender.isEnabled()) {
    sender.start();
}
```

---

## 电脑端连接

### 方式 1：直接访问 Workers API

在浏览器中打开：
```
https://tv-live-cloud-log-server.your-subdomain.workers.dev/api/logs
```

查看所有设备的日志。

### 方式 2：更新电脑端监控工具

在电脑端监控工具的「☁️ 云端」标签中：

1. 输入 Workers 地址：`https://tv-live-cloud-log-server.your-subdomain.workers.dev`
2. 点击「连接云端服务器」
3. 即可查看所有设备的日志

---

## API 接口说明

### 基础 URL
```
https://tv-live-cloud-log-server.your-subdomain.workers.dev
```

### 接口列表

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/status` | 获取服务状态 |
| POST | `/api/device/register` | 设备注册 |
| GET | `/api/devices` | 获取设备列表 |
| POST | `/api/logs/:deviceId` | 发送日志 |
| GET | `/api/logs` | 获取所有日志 |
| GET | `/api/logs/:deviceId` | 获取指定设备日志 |
| DELETE | `/api/logs` | 清空所有日志 |
| DELETE | `/api/logs/:deviceId` | 清空指定设备日志 |
| GET | `/api/stream` | 获取实时日志流（SSE） |

### 请求示例

#### 设备注册
```bash
POST /api/device/register
Content-Type: application/json

{
  "deviceId": "unique-device-id",
  "deviceName": "我的手机",
  "deviceModel": "Xiaomi 13",
  "appVersion": "1.0.0",
  "brand": "Xiaomi",
  "manufacturer": "Xiaomi"
}
```

#### 发送日志
```bash
POST /api/logs/device-id
Content-Type: application/json

{
  "logs": [
    {
      "logType": "info",
      "message": "这是一条日志",
      "timestamp": 1700000000000
    }
  ]
}
```

#### 获取日志
```bash
# 获取所有日志
GET /api/logs

# 获取指定设备日志
GET /api/logs/device-id

# 按类型过滤
GET /api/logs?type=network

# 限制数量
GET /api/logs?limit=100
```

---

## 常见问题

### Q1：免费额度是多少？

Cloudflare Workers 免费版：
- 每天 100,000 次请求
- 30 个 Workers
- KV 存储 1GB
- 10ms CPU 时间/请求

对于个人使用完全足够！

### Q2：支持 WebSocket 吗？

Cloudflare Workers 不支持 WebSocket 服务端。本项目使用 HTTP 轮询方式获取日志，功能相同。

### Q3：KV 存储会自动过期吗？

不会，KV 存储中的数据需要手动删除。建议定期清理旧日志。

### Q4：如何清理日志？

```bash
# 清空所有日志
DELETE /api/logs

# 清空指定设备日志
DELETE /api/logs/device-id
```

### Q5：如何升级到付费版？

如需更多额度：
- 付费版 $5/月
- 每天 10,000,000 次请求
- 100ms CPU 时间/请求
- 无限 KV 存储

在 Cloudflare 控制台 → Workers → Plans 中升级。

### Q6：国内访问速度如何？

Cloudflare 在全球有 300+ 节点，包括中国大陆的 CDN 节点，访问速度通常很快。

### Q7：如何设置 API_KEY 保护？

编辑 `wrangler.toml`：

```toml
[vars]
API_KEY = "your-secret-key"
```

然后在请求头中携带：
```
x-api-key: your-secret-key
```

### Q8：如何更新 Worker？

修改代码后：
```bash
wrangler deploy
```

会自动部署新版本。

---

## 版本历史

- **v1.0.0** (2024-01-01)
  - 初始版本
  - 支持设备注册和日志接收
  - 支持 HTTP 轮询获取日志
  - 支持 SSE 实时日志流
  - 使用 KV 存储
