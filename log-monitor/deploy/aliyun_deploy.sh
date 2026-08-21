#!/bin/bash
# TV Live 云端日志服务器 - 一键部署脚本
# 支持 Ubuntu/Debian/CentOS 系统
# 集成宝塔面板和 PM2

set -e

# 配置变量
APP_NAME="tv-live-cloud"
APP_DIR="/www/wwwroot/${APP_NAME}"
NODE_VERSION="18"
DOMAIN=""
API_KEY=""
PORT=8080

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

log_info() { echo -e "${GREEN}[INFO]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

# 检查是否为 root 用户
check_root() {
    if [[ $EUID -ne 0 ]]; then
        log_error "此脚本需要 root 权限运行"
        exit 1
    fi
}

# 检测操作系统
detect_os() {
    if [[ -f /etc/os-release ]]; then
        . /etc/os-release
        OS=$ID
        OS_VERSION=$VERSION_ID
    elif [[ -f /etc/redhat-release ]]; then
        OS="centos"
        OS_VERSION=$(cat /etc/redhat-release | grep -oP '\d+' | head -1)
    else
        log_error "无法检测操作系统"
        exit 1
    fi
    
    log_info "检测到操作系统: $OS $OS_VERSION"
}

# 安装 Node.js
install_nodejs() {
    if command -v node &> /dev/null; then
        NODE_VER=$(node --version | cut -d'v' -f2 | cut -d'.' -f1)
        if [[ $NODE_VER -ge 16 ]]; then
            log_info "Node.js $(node --version) 已安装"
            return 0
        fi
    fi
    
    log_info "安装 Node.js $NODE_VERSION ..."
    
    if [[ "$OS" == "ubuntu" ]] || [[ "$OS" == "debian" ]]; then
        curl -fsSL https://deb.nodesource.com/setup_${NODE_VERSION}.x | bash -
        apt-get install -y nodejs
    elif [[ "$OS" == "centos" ]] || [[ "$OS" == "rhel" ]] || [[ "$OS" == "almalinux" ]]; then
        curl -fsSL https://rpm.nodesource.com/setup_${NODE_VERSION}.x | bash -
        yum install -y nodejs
    else
        log_error "不支持的操作系统"
        exit 1
    fi
    
    log_info "Node.js 安装完成: $(node --version)"
    log_info "npm 安装完成: $(npm --version)"
}

# 安装 PM2
install_pm2() {
    if command -v pm2 &> /dev/null; then
        log_info "PM2 已安装"
        return 0
    fi
    
    log_info "安装 PM2 ..."
    npm install -g pm2
    log_info "PM2 安装完成: $(pm2 --version)"
}

# 创建应用目录
setup_app_dir() {
    if [[ -d "$APP_DIR" ]]; then
        log_warn "应用目录 $APP_DIR 已存在，将备份旧文件"
        mv "$APP_DIR" "${APP_DIR}_backup_$(date +%s)"
    fi
    
    mkdir -p "$APP_DIR"
    cd "$APP_DIR"
    
    log_info "应用目录已创建: $APP_DIR"
}

# 安装项目依赖
install_dependencies() {
    log_info "安装项目依赖 ..."
    cd "$APP_DIR"
    
    # 创建 package.json
    cat > package.json << 'EOF'
{
  "name": "tv-live-cloud-log-server",
  "version": "1.0.0",
  "description": "TV Live 云端日志中转服务器",
  "main": "cloud-server.js",
  "scripts": {
    "start": "node cloud-server.js",
    "dev": "node cloud-server.js"
  },
  "dependencies": {
    "ws": "^8.16.0",
    "express": "^4.18.2",
    "cors": "^2.8.5"
  }
}
EOF
    
    npm install --production
    log_info "依赖安装完成"
}

# 创建环境配置文件
create_env_file() {
    log_info "创建环境配置文件 ..."
    
    cat > "$APP_DIR/.env" << EOF
# TV Live 云端日志服务器配置
# 服务器端口
PORT=8080

# API 密钥（留空则不启用认证）
# 生产环境建议设置强密钥
API_KEY=${API_KEY:-}

# 保留日志最大数量（每台设备）
MAX_LOGS_PER_DEVICE=10000

# 设备过期时间（毫秒），默认24小时
DEVICE_EXPIRE_MS=86400000
EOF
    
    log_info "环境配置文件已创建: $APP_DIR/.env"
}

# 创建 PM2 配置
create_pm2_config() {
    log_info "创建 PM2 配置 ..."
    
    cat > "$APP_DIR/ecosystem.config.js" << 'EOF'
module.exports = {
  apps: [{
    name: 'tv-live-cloud',
    script: 'cloud-server.js',
    cwd: '/www/wwwroot/tv-live-cloud',
    instances: 1,
    autorestart: true,
    watch: false,
    max_memory_restart: '512M',
    env: {
      NODE_ENV: 'production',
      PORT: 8080
    },
    error_file: '/www/wwwroot/tv-live-cloud/logs/error.log',
    out_file: '/www/wwwroot/tv-live-cloud/logs/output.log',
    log_date_format: 'YYYY-MM-DD HH:mm:ss Z',
    merge_logs: true
  }]
};
EOF
    
    # 创建日志目录
    mkdir -p "$APP_DIR/logs"
    
    log_info "PM2 配置已创建"
}

# 设置防火墙
setup_firewall() {
    log_info "配置防火墙 ..."
    
    if command -v ufw &> /dev/null; then
        ufw allow 8080/tcp
        ufw allow 80/tcp
        ufw allow 443/tcp
        log_info "UFW 防火墙已配置"
    elif command -v firewall-cmd &> /dev/null; then
        firewall-cmd --permanent --add-port=8080/tcp
        firewall-cmd --permanent --add-port=80/tcp
        firewall-cmd --permanent --add-port=443/tcp
        firewall-cmd --reload
        log_info "Firewalld 防火墙已配置"
    else
        log_warn "未检测到防火墙，请手动开放 8080/80/443 端口"
    fi
}

# 启动服务
start_service() {
    log_info "启动云端日志服务 ..."
    
    cd "$APP_DIR"
    
    # 停止可能存在的旧进程
    pm2 delete tv-live-cloud 2>/dev/null || true
    
    # 启动新服务
    pm2 start ecosystem.config.js --env production
    
    # 保存 PM2 配置
    pm2 save
    
    # 设置开机自启
    pm2 startup
    
    log_info "服务已启动"
    log_info "查看服务状态: pm2 status"
    log_info "查看日志: pm2 logs tv-live-cloud"
}

# 安装宝塔面板
install_bt_panel() {
    if command -v bt &> /dev/null; then
        log_info "宝塔面板已安装"
        return 0
    fi
    
    log_info "正在安装宝塔面板 ..."
    log_warn "请稍候，宝塔面板安装需要几分钟时间..."
    
    if [[ "$OS" == "ubuntu" ]] || [[ "$OS" == "debian" ]]; then
        curl -s http://download.bt.cn/install/install-ubuntu_6.0.sh | bash
    elif [[ "$OS" == "centos" ]] || [[ "$OS" == "rhel" ]] || [[ "$OS" == "almalinux" ]]; then
        yum install -y wget && wget -O install.sh http://download.bt.cn/install/install_6.0.sh && sh install.sh
    fi
    
    log_info "宝塔面板安装完成"
    log_info "请查看上面的输出获取面板地址和初始密码"
}

# 显示完成信息
show_completion() {
    local IP=$(hostname -I 2>/dev/null | awk '{print $1}' || echo '<服务器IP>')
    
    echo ""
    echo "╔══════════════════════════════════════════════════════════════╗"
    echo "║           TV Live 云端日志服务器部署完成                     ║"
    echo "╠══════════════════════════════════════════════════════════════╣"
    echo "║                                                            ║"
    echo "║  🌐 服务地址（HTTP）: http://${IP}:8080                      ║"
    echo "║  📡 WebSocket地址  : ws://${IP}:8080/ws                      ║"
    echo "║  📝 日志接收API   : POST http://${IP}:8080/api/logs/:deviceId║"
    echo "║  📊 状态检查      : GET  http://${IP}:8080/api/status        ║"
    echo "║                                                            ║"
    echo "╠══════════════════════════════════════════════════════════════╣"
    echo "║  📱 手机端配置                                             ║"
    echo "║                                                            ║"
    echo "║  CloudLogSender.getInstance(context).setServerUrl(         ║"
    echo "║      \"http://${IP}:8080\"                                 ║"
    echo "║  );                                                        ║"
    echo "║  CloudLogSender.getInstance(context).setEnabled(true);      ║"
    echo "║  CloudLogSender.getInstance(context).start();               ║"
    echo "║                                                            ║"
    echo "╠══════════════════════════════════════════════════════════════╣"
    echo "║  🔧 常用命令                                               ║"
    echo "║                                                            ║"
    echo "║  查看服务状态: pm2 status                                   ║"
    echo "║  查看实时日志: pm2 logs tv-live-cloud                       ║"
    echo "║  重启服务    : pm2 restart tv-live-cloud                    ║"
    echo "║  停止服务    : pm2 stop tv-live-cloud                       ║"
    echo "║  启动服务    : pm2 start tv-live-cloud                      ║"
    echo "║                                                            ║"
    echo "╠══════════════════════════════════════════════════════════════╣"
    echo "║  ⚠️  重要提醒                                              ║"
    echo "║                                                            ║"
    echo "║  1. 请在阿里云安全组开放 8080 端口                         ║"
    echo "║  2. 生产环境建议设置 API_KEY                                ║"
    echo "║  3. 建议使用 Nginx + SSL 反向代理                         ║"
    echo "║  4. 定期检查日志存储空间                                   ║"
    echo "║                                                            ║"
    echo "╚══════════════════════════════════════════════════════════════╝"
    echo ""
}

# 主函数
main() {
    echo ""
    echo "╔══════════════════════════════════════════════════════════════╗"
    echo "║     TV Live 云端日志服务器 - 一键部署脚本                    ║"
    echo "║     支持 Ubuntu/Debian/CentOS 系统                          ║"
    echo "╚══════════════════════════════════════════════════════════════╝"
    echo ""
    
    # 询问配置
    read -p "请输入服务器端口 [默认: 8080]: " PORT
    PORT=${PORT:-8080}
    
    read -p "请输入 API 密钥（留空不启用认证）: " API_KEY
    API_KEY=${API_KEY:-}
    
    read -p "是否安装宝塔面板? [y/N]: " INSTALL_BT
    INSTALL_BT=${INSTALL_BT:-n}
    
    echo ""
    log_info "开始部署..."
    echo ""
    
    # 执行部署
    check_root
    detect_os
    install_nodejs
    install_pm2
    
    if [[ "$INSTALL_BT" == "y" ]] || [[ "$INSTALL_BT" == "Y" ]]; then
        install_bt_panel
    fi
    
    setup_app_dir
    install_dependencies
    create_env_file
    create_pm2_config
    setup_firewall
    start_service
    show_completion
}

# 运行主函数
main "$@"
