/**
 * TV Live 云端日志服务器 - PM2 配置
 * 
 * 启动命令: pm2 start ecosystem.config.js
 * 查看状态: pm2 status
 * 查看日志: pm2 logs tv-live-cloud
 * 重启服务: pm2 restart tv-live-cloud
 * 停止服务: pm2 stop tv-live-cloud
 */

module.exports = {
  apps: [
    {
      // 应用名称
      name: 'tv-live-cloud',
      
      // 入口文件
      script: 'cloud-server.js',
      
      // 工作目录
      cwd: '/www/wwwroot/tv-live-cloud',
      
      // 实例数量（单实例即可）
      instances: 1,
      
      // 生产环境变量
      env: {
        NODE_ENV: 'production',
        PORT: 8080,
        API_KEY: process.env.API_KEY || '',
        MAX_LOGS_PER_DEVICE: 10000,
        DEVICE_EXPIRE_MS: 86400000
      },
      
      // 开发环境变量
      env_development: {
        NODE_ENV: 'development',
        PORT: 8080
      },
      
      // 自动重启
      autorestart: true,
      
      // 监控文件变化（不建议生产环境开启）
      watch: false,
      ignore_watch: ['node_modules', 'logs', '.env'],
      
      // 内存超过 512M 自动重启
      max_memory_restart: '512M',
      
      // 错误日志
      error_file: '/www/wwwroot/tv-live-cloud/logs/error.log',
      
      // 输出日志
      out_file: '/www/wwwroot/tv-live-cloud/logs/output.log',
      
      // 日志格式
      log_date_format: 'YYYY-MM-DD HH:mm:ss Z',
      
      // 合并日志（多实例时有用）
      merge_logs: true,
      
      // 日志文件大小限制
      max_size: '10M',
      
      // 保留最近的日志文件数
      retain: 7,
      
      // 压缩旧日志
      compress: true,
      
      // 启动延迟时间（秒）
      wait_ready: false,
      
      // 自动杀掉旧进程
      kill_timeout: 5000,
      
      // 重启延迟
      restart_delay: 1000,
      
      // 最大重启次数（无限）
      max_restarts: 0,
      
      // 最小运行时间（秒），低于此时间的重启视为异常
      min_uptime: '10s',
      
      // 事件循环延迟警告阈值（毫秒）
      kill_timeout: 5000
    }
  ]
};
