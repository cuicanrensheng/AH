@echo off
chcp 65001 >nul
title TV Live 日志监控

cd /d "%~dp0"

rem 检查 Electron 是否可用
set "ELECTRON_PATH=node_modules\electron\dist\electron.exe"

if exist "%ELECTRON_PATH%" (
    echo 正在启动 TV Live 日志监控...
    start "" "%ELECTRON_PATH%" .
) else (
    echo 正在使用 Node.js 启动服务器...
    start "" node server.js
    timeout /t 3 /nobreak >nul
    start "" http://localhost:3000
)

exit
