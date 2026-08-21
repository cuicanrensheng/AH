@echo off
chcp 65001 >nul 2>&1
title TV Live 日志监控 - 内网穿透

echo ════════════════════════════════════════════════════════════════
echo   内网穿透 - 让外网手机连接到本地服务
echo ════════════════════════════════════════════════════════════════
echo.

:: 检查是否已有服务在运行
netstat -ano | findstr ":8080" | findstr "LISTENING" >nul 2>&1
if %errorlevel% neq 0 (
    echo [警告] 本地服务未启动！请先运行「启动本地远程服务.bat」
    echo.
    choice /c yn /m "是否现在启动本地服务"
    if errorlevel 2 (
        echo 已取消
        pause
        exit /b 1
    )
    echo [启动] 正在后台启动本地服务...
    start /b node cloud-server.js
    timeout /t 3 >nul
)

:: 检查 cpolar
echo [检查] 正在查找内网穿透工具...
echo.

:: 方式1: 检查 cpolar
where cpolar >nul 2>&1
if %errorlevel% equ 0 (
    echo [找到] cpolar 已安装
    echo [启动] 正在启动 cpolar 隧道...
    echo.
    echo ┌─────────────────────────────────────────────────────────────┐
    echo │  cpolar 正在启动，请等待公网地址生成...                       │
    echo │  生成后复制公网地址，填入手机 App 中即可                     │
    echo └─────────────────────────────────────────────────────────────┘
    echo.
    cpolar http 8080
    pause
    exit /b 0
)

:: 方式2: 检查 ngrok
where ngrok >nul 2>&1
if %errorlevel% equ 0 (
    echo [找到] ngrok 已安装
    echo [启动] 正在启动 ngrok 隧道...
    echo.
    ngrok http 8080
    pause
    exit /b 0
)

:: 方式3: 检查常见安装路径
set "CPOLAR_FOUND="
for %%p in (
    "%USERPROFILE%\cpolar\cpolar.exe"
    "%ProgramFiles%\cpolar\cpolar.exe"
    "%ProgramFiles(x86)%\cpolar\cpolar.exe"
    "%LOCALAPPDATA%\cpolar\cpolar.exe"
) do (
    if exist %%p (
        set "CPOLAR_FOUND=%%p"
    )
)

if defined CPOLAR_FOUND (
    echo [找到] cpolar: %CPOLAR_FOUND%
    echo [启动] 正在启动内网穿透...
    echo.
    "%CPOLAR_FOUND%" http 8080
    pause
    exit /b 0
)

:: 未找到任何工具
echo [未找到] 没有检测到内网穿透工具
echo.
echo ════════════════════════════════════════════════════════════════
echo   请选择一个内网穿透工具（都是免费的）:
echo ════════════════════════════════════════════════════════════════
echo.
echo   1. cpolar（推荐 - 国内速度最快）
echo      下载: https://www.cpolar.com/download
echo      注册后免费使用，国内节点多
echo.
echo   2. ngrok（国际通用）
echo      下载: https://ngrok.com/download
echo      注册后免费使用，国际节点
echo.
echo   3. 花生壳（国内老牌）
echo      下载: https://hsk.oray.com/download/
echo      免费版提供 1 条隧道
echo.
echo ════════════════════════════════════════════════════════════════
echo.
echo  安装完成后，重新运行此脚本即可自动启动！
echo.

:: 打开下载页面
choice /c 123n /m "选择下载哪个工具 (1=cpolar 2=ngrok 3=花生壳 n=不下载)"
if errorlevel 4 goto :end
if errorlevel 3 start https://hsk.oray.com/download/
if errorlevel 2 start https://ngrok.com/download
if errorlevel 1 start https://www.cpolar.com/download

:end
pause
