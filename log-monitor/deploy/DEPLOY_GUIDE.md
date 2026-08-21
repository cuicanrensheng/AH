# TV Live 云端日志服务器 - 部署指南

## 📋 目录

1. [购买阿里云服务器](#购买阿里云服务器)
2. [快速部署（推荐）](#快速部署推荐)
3. [宝塔面板部署](#宝塔面板部署)
4. [手动部署](#手动部署)
5. [配置域名和 SSL](#配置域名和-ssl)
6. [手机端集成](#手机端集成)
7. [电脑端监控](#电脑端监控)
8. [常见问题](#常见问题)

---

## 购买阿里云服务器

### 推荐配置

| 配置项 | 推荐值 | 说明 |
|--------|--------|------|
| 实例规格 | ecs.t5-lc1m1.small | 1核2G，足够运行 |
| 操作系统 | Ubuntu 22.04 LTS | 稳定可靠 |
| 磁盘 | 40GB ESSD | 存储日志用 |
| 带宽 | 1Mbps 按需 | 或包年包月 |
| 安全组 | 开放 22/80/443/8080 端口 | 按需调整 |

### 购买步骤

1. 访问 [阿里云 ECS](https://www.aliyun.com/product/ecs) 页面
2. 选择「轻量应用服务器」或「云服务器 ECS」
3. 选择配置：**1核2G + Ubuntu 22.04**
4. 设置 root 密码（请牢记）
5. 购买并等待实例启动

### 开放端口

在阿里云控制台：
1. 进入「实例与镜像」→ 选择您的实例
2. 点击「安全组」→「配置规则」
3. 添加以下端口：
   - **22** (SSH) - TCP
   - **80** (HTTP) - TCP
   - **443** (HTTPS) - TCP
   - **8080** (日志服务) - TCP

---

## 快速部署（推荐）

### 步骤 1：下载部署脚本

将以下文件上传到服务器：
- `aliyun_deploy.sh`
- `cloud-server.js`（从项目 `log-monitor/` 目录复制）

```bash
# 在本地电脑执行（使用 Git Bash 或 WSL）
scp aliyun_deploy.sh root@你的服务器IP:/root/
scp cloud-server.js root@你的服务器IP:/root/
```

### 步骤 2：连接服务器

```bash
ssh root@你的服务器IP
```

### 步骤 3：执行部署脚本

```bash
cd /root
chmod +x aliyun_deploy.sh
./aliyun_deploy.sh
```

部署脚本会自动：
- ✅ 安装 Node.js 18
- ✅ 安装 PM2 进程管理器
- ✅ 安装宝塔面板（可选）
- ✅ 配置防火墙规则
- ✅ 启动日志服务

### 步骤 4：验证服务

```bash
# 查看服务状态
pm2 status

# 查看日志
pm2 logs tv-live-cloud

# 测试 API
curl http://localhost:8080/api/status
```

---

## 宝塔面板部署

### 步骤 1：安装宝塔面板

如果使用一键部署脚本，会自动安装。否则手动执行：

```bash
# Ubuntu/Debian
curl -s http://download.bt.cn/install/install-ubuntu_6.0.sh | bash

# CentOS
yum install -y wget && wget -O install.sh http://download.bt.cn/install/install_6.0.sh && sh install.sh
```

安装完成后会显示：
- 面板地址：`http://你的IP:8888/xxxx`
- 初始账号：`admin`
- 初始密码：`xxxxxx`

### 步骤 2：登录宝塔面板

1. 浏览器打开宝塔面板地址
2. 首次登录后修改密码
3. 绑定宝塔账号（可选）

### 步骤 3：安装 Nginx

1. 点击左侧「软件商店」
2. 搜索「Nginx」
3. 选择「Nginx 1.24」点击「立即安装」
4. 保持默认配置，点击「提交」

### 步骤 4：添加站点

1. 点击左侧「网站」→「添加站点」
2. 填写信息：
   - **域名**：填写您的域名（如 `log.yourdomain.com`），或填服务器 IP 测试
   - **根目录**：`/www/wwwroot/tv-live-cloud`
   - **PHP版本**：纯静态
   - **数据库**：不创建
3. 点击「提交」

### 步骤 5：配置反向代理

1. 在站点列表点击刚创建的站点名
2. 点击左侧「设置」→「配置文件」
3. 将 `nginx_tv_live_cloud.conf` 的内容复制到配置文件中
4. 替换 `your-domain.com` 为您的实际域名
5. 点击「保存」

### 步骤 6：上传项目文件

```bash
# 使用宝塔面板的文件管理器
# 将 cloud-server.js 上传到 /www/wwwroot/tv-live-cloud/
# 创建 package.json 文件
```

或者在服务器上执行：

```bash
cd /www/wwwroot/tv-live-cloud

# 创建 package.json
cat > package.json << 'EOF'
{
  "name": "tv-live-cloud-log-server",
  "version": "1.0.0",
  "main": "cloud-server.js",
  "dependencies": {
    "ws": "^8.16.0",
    "express": "^4.18.2",
    "cors": "^2.8.5"
  }
}
EOF

# 安装依赖
npm install --production
```

### 步骤 7：使用 PM2 启动服务

```bash
# 使用宝塔面板的 Node.js 管理器
# 或使用 PM2 命令行

pm2 install pm2-logrotate  # 日志轮转

cd /www/wwwroot/tv-live-cloud
pm2 start cloud-server.js --name tv-live-cloud
pm2 save
pm2 startup  # 设置开机自启
```

---

## 手动部署

### 完整步骤

```bash
# 1. 更新系统
apt update && apt upgrade -y

# 2. 安装 Node.js 18
curl -fsSL https://deb.nodesource.com/setup_18.x | bash -
apt install -y nodejs

# 3. 安装 PM2
npm install -g pm2 pm2-logrotate

# 4. 创建项目目录
mkdir -p /www/wwwroot/tv-live-cloud
cd /www/wwwroot/tv-live-cloud

# 5. 创建 package.json
cat > package.json << 'EOF'
{
  "name": "tv-live-cloud-log-server",
  "version": "1.0.0",
  "main": "cloud-server.js",
  "dependencies": {
    "ws": "^8.16.0",
    "express": "^4.18.2",
    "cors": "^2.8.5"
  }
}
EOF

# 6. 上传 cloud-server.js 到此目录
# 使用 scp 或宝塔文件管理器

# 7. 安装依赖
npm install --production

# 8. 创建日志目录
mkdir -p logs

# 9. 创建环境配置
cat > .env << 'EOF'
PORT=8080
API_KEY=your-secret-key
MAX_LOGS_PER_DEVICE=10000
DEVICE_EXPIRE_MS=86400000
EOF

# 10. 启动服务
pm2 start cloud-server.js --name tv-live-cloud
pm2 save
pm2 startup

# 11. 配置防火墙
ufw allow 8080/tcp
ufw allow 80/tcp
ufw allow 443/tcp

# 12. 查看状态
pm2 status
curl http://localhost:8080/api/status
```

---

## 配置域名和 SSL

### 1. 域名解析

在您的域名服务商处添加 DNS 记录：

| 记录类型 | 主机记录 | 记录值 |
|---------|---------|--------|
| A | log | 你的服务器IP |

等待 DNS 生效（通常几分钟到几小时）。

### 2. 申请 SSL 证书

#### 方法一：宝塔面板一键申请（推荐）

1. 宝塔面板 → 网站 → 点击站点
2. 设置 → SSL → Let's Encrypt
3. 勾选域名，点击「申请」
4. 申请成功后开启「强制HTTPS」

#### 方法二：使用 certbot

```bash
# 安装 certbot
apt install certbot python3-certbot-nginx -y

# 申请证书
certbot --nginx -d log.yourdomain.com

# 自动续期已内置
certbot renew --dry-run  # 测试续期
```

### 3. 更新 Nginx 配置

在宝塔面板的站点配置中：
1. 取消注释 SSL 相关配置
2. 配置证书路径
3. 可选：开启 HTTP 强制跳转 HTTPS

---

## 手机端集成

### 在 MyApplication 中初始化

```java
// 在 Application.onCreate() 中添加
CloudLogSender cloudLogSender = CloudLogSender.getInstance(this);
cloudLogSender.setServerUrl("https://log.yourdomain.com");
cloudLogSender.setApiKey("your-secret-key");  // 可选
cloudLogSender.setEnabled(true);
cloudLogSender.start();
```

### 或在设置页面动态配置

```java
// 从 SharedPreferences 读取配置
SharedPreferences prefs = getSharedPreferences("cloud_log_config", MODE_PRIVATE);
String serverUrl = prefs.getString("server_url", "");
boolean enabled = prefs.getBoolean("enabled", false);

CloudLogSender sender = CloudLogSender.getInstance(context);
sender.setServerUrl(serverUrl);
sender.setEnabled(enabled);
if (enabled) {
    sender.start();
}
```

### 在设置页面添加开关

```xml
<!-- res/layout/activity_settings.xml -->
<com.google.android.material.switchmaterial.SwitchMaterial
    android:id="@+id/switch_cloud_log"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:text="开启云端日志" />

<EditText
    android:id="@+id/edit_cloud_server_url"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:hint="云端服务器地址"
    android:inputType="textUri" />
```

```java
// 设置页面逻辑
SwitchMaterial switchCloud = findViewById(R.id.switch_cloud_log);
EditText editServerUrl = findViewById(R.id.edit_cloud_server_url);

SharedPreferences prefs = getSharedPreferences("cloud_log_config", MODE_PRIVATE);
switchCloud.setChecked(prefs.getBoolean("enabled", false));
editServerUrl.setText(prefs.getString("server_url", ""));

switchCloud.setOnCheckedChangeListener((buttonView, isChecked) -> {
    prefs.edit().putBoolean("enabled", isChecked).apply();
    CloudLogSender.getInstance(this).setEnabled(isChecked);
    if (isChecked) {
        CloudLogSender.getInstance(this).start();
    }
});

editServerUrl.addTextChangedListener(new TextWatcher() {
    @Override
    public void afterTextChanged(Editable s) {
        prefs.edit().putString("server_url", s.toString()).apply();
        CloudLogSender.getInstance(this).setServerUrl(s.toString());
    }
    // ...
});
```

---

## 电脑端监控

### 1. 启动本地日志监控工具

```bash
cd log-monitor
npm run start:dev
# 或
npm run build:portable  # 生成 exe 文件
```

### 2. 连接云端

1. 打开日志监控工具
2. 切换到「☁️ 云端」标签
3. 输入云端服务器地址：`https://log.yourdomain.com`
4. 输入 API 密钥（如有）
5. 点击「连接云端服务器」

### 3. 查看日志

- **实时日志**：连接后自动接收
- **历史日志**：点击「手动同步日志」
- **设备列表**：显示所有在线设备
- **筛选视图**：仅看网络/播放/调试/崩溃

---

## 常用命令

### PM2 进程管理

```bash
# 查看状态
pm2 status

# 查看日志
pm2 logs tv-live-cloud
pm2 logs tv-live-cloud --lines 100  # 最近100行

# 重启服务
pm2 restart tv-live-cloud

# 停止服务
pm2 stop tv-live-cloud

# 启动服务
pm2 start tv-live-cloud

# 删除服务
pm2 delete tv-live-cloud

# 监控面板
pm2 monit
```

### 日志管理

```bash
# 清理旧日志
pm2 flush tv-live-cloud

# 日志轮转（已配置 pm2-logrotate）
# 保留 7 天，最大 10M
```

### Nginx 管理

```bash
# 测试配置
nginx -t

# 重新加载配置
nginx -s reload

# 重启 Nginx
systemctl restart nginx
```

### 服务器资源监控

```bash
# CPU 和内存
top

# 磁盘使用
df -h

# 网络连接
netstat -tuln | grep 8080
```

---

## 常见问题

### Q1: 部署后无法访问？

**检查清单**：
1. 确认 PM2 服务运行中：`pm2 status`
2. 确认端口监听：`netstat -tuln | grep 8080`
3. 确认阿里云安全组开放端口
4. 确认服务器防火墙开放端口：`ufw status`
5. 测试本地访问：`curl http://localhost:8080/api/status`

### Q2: PM2 服务启动失败？

**排查步骤**：
```bash
# 查看错误日志
pm2 logs tv-live-cloud --err

# 检查 Node.js 版本
node --version  # 需要 >= 16

# 重新安装依赖
cd /www/wwwroot/tv-live-cloud
rm -rf node_modules
npm install --production
```

### Q3: WebSocket 连接失败？

**检查清单**：
1. Nginx 配置中包含 WebSocket 支持
2. 检查 SSL 证书是否有效
3. 手机端使用正确的协议（ws:// 或 wss://）
4. 检查 API_KEY 是否匹配

### Q4: 日志接收延迟？

**可能原因**：
- 手机端网络不稳定
- 云端服务器负载过高
- PM2 内存不足自动重启

**优化方案**：
1. 增加服务器内存
2. 检查 PM2 日志
3. 调整手机端发送频率

### Q5: 如何升级服务？

```bash
# 1. 停止服务
pm2 stop tv-live-cloud

# 2. 备份旧版本
cd /www/wwwroot/tv-live-cloud
cp cloud-server.js cloud-server.js.bak

# 3. 上传新版本 cloud-server.js

# 4. 安装新依赖（如有）
npm install --production

# 5. 重启服务
pm2 start tv-live-cloud

# 6. 验证
curl http://localhost:8080/api/status
```

### Q6: 如何备份和恢复？

```bash
# 备份
cd /www/wwwroot
tar -czf tv-live-cloud-backup-$(date +%Y%m%d).tar.gz tv-live-cloud/

# 恢复
tar -xzf tv-live-cloud-backup-20240101.tar.gz
```

---

## 安全建议

1. **设置 API_KEY**：在 `.env` 中设置强随机密钥
2. **使用 HTTPS**：生产环境必须启用 SSL
3. **定期更新**：保持系统和依赖更新
4. **监控日志**：定期检查错误日志
5. **备份数据**：定期备份服务配置和日志

### 生成强密钥

```bash
# Linux
openssl rand -hex 32

# 或使用 Node.js
node -e "console.log(require('crypto').randomBytes(32).toString('hex'))"
```

---

## 技术支持

如果遇到问题，请检查：
1. PM2 错误日志：`pm2 logs tv-live-cloud --err`
2. Nginx 错误日志：`tail -f /www/wwwlogs/tv-live-cloud-error.log`
3. 系统日志：`journalctl -u nginx`

---

## 版本历史

- **v1.0.0** (2024-01-01)
  - 初始版本
  - 支持设备注册和日志接收
  - 支持 WebSocket 实时推送
  - 支持 HTTP 批量发送
  - 支持宝塔面板一键部署
