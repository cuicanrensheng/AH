@echo off
chcp 65001 >nul 2>&1
title TV Live 日志监控 - 本地远程服务

echo ════════════════════════════════════════════════════════════════
echo   TV Live 日志监控 - 本地远程接收服务
echo ════════════════════════════════════════════════════════════════
echo.

:: 检查 Node.js
where node >nul 2>&1
if %errorlevel% neq 0 (
    echo [错误] 未检测到 Node.js，请先安装: https://nodejs.org/
    pause
    exit /b 1
)

:: 检查依赖
if not exist "node_modules" (
    echo [安装] 首次运行，正在安装依赖...
    call npm install
    if %errorlevel% neq 0 (
        echo [错误] 依赖安装失败
        pause
        exit /b 1
    )
    echo [完成] 依赖安装成功
    echo.
)

:: 获取本机局域网 IP
echo [信息] 正在获取本机网络地址...
for /f "tokens=2 delims=:" %%a in ('ipconfig ^| findstr /i "IPv4"') do (
    set "LAN_IP=%%a"
    set "LAN_IP=!LAN_IP: =!"
)

:: 启动云端日志服务器（端口 8080）
echo [启动] 正在启动日志服务器 (端口 8080)...
echo.
echo ┌─────────────────────────────────────────────────────────────┐
echo │  电脑端监控页面: http://localhost:8080                      │
echo │  手机连接地址:   http://本机IP:8080                        │
echo │  外网地址:       需要启动内网穿透（见下方提示）              │
echo └─────────────────────────────────────────────────────────────┘
echo.
echo [提示] 手机和电脑在同一 WiFi 下可直接用局域网 IP 连接
echo [提示] 外网连接请运行: 启动内网穿透.bat
echo.

:: 启动服务器
node cloud-server.js

pause
