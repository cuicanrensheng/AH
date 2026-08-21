const { app, BrowserWindow, Tray, Menu, nativeImage } = require('electron');
const path = require('path');
const fs = require('fs');

let mainWindow = null;
let tray = null;
const SERVER_PORT = 3000;

// 查找服务器脚本 - 可能在不同位置
function findServerScript() {
  const possiblePaths = [
    path.join(__dirname, '..', 'server.js'),
    path.join(__dirname, 'server.js'),
    path.join(process.resourcesPath || '', 'app', 'server.js'),
    path.join(process.resourcesPath || '', 'server.js'),
  ];
  
  for (const p of possiblePaths) {
    if (fs.existsSync(p)) {
      console.log(`[Server] Found server script at: ${p}`);
      return p;
    }
  }
  return null;
}

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1400,
    height: 900,
    minWidth: 1000,
    minHeight: 600,
    title: 'TV Live 日志监控',
    icon: getIconPath(),
    webPreferences: {
      nodeIntegration: false,
      contextIsolation: true,
      webSecurity: true
    },
    backgroundColor: '#0f1419',
    autoHideMenuBar: true
  });

  mainWindow.loadURL(`http://localhost:${SERVER_PORT}`);

  mainWindow.on('closed', () => {
    mainWindow = null;
  });

  mainWindow.on('minimize', (event) => {
    event.preventDefault();
    mainWindow.hide();
  });
}

function getIconPath() {
  const possiblePaths = [
    path.join(__dirname, '..', 'assets', 'icon.png'),
    path.join(__dirname, 'assets', 'icon.png'),
    path.join(__dirname, '..', 'icon.png'),
    path.join(__dirname, 'icon.png'),
  ];
  
  for (const p of possiblePaths) {
    if (fs.existsSync(p)) {
      return p;
    }
  }
  return null;
}

function createTray() {
  const iconPath = getIconPath();
  let trayIcon;
  
  try {
    if (iconPath) {
      trayIcon = nativeImage.createFromPath(iconPath);
    }
  } catch (e) {
    console.log('Icon not found, using default');
  }
  
  if (!trayIcon || trayIcon.isEmpty()) {
    trayIcon = nativeImage.createEmpty();
  }
  
  tray = new Tray(trayIcon);
  tray.setToolTip('TV Live 日志监控');

  const contextMenu = Menu.buildFromTemplate([
    { label: '显示主窗口', click: () => { if (mainWindow) mainWindow.show(); } },
    { type: 'separator' },
    { label: '退出', click: () => { 
      app.quit(); 
    }}
  ]);

  tray.setContextMenu(contextMenu);

  tray.on('click', () => {
    if (mainWindow) {
      if (mainWindow.isVisible()) {
        mainWindow.hide();
      } else {
        mainWindow.show();
      }
    }
  });
}

// 直接在主进程中启动服务器
function startServer() {
  return new Promise((resolve, reject) => {
    const serverScript = findServerScript();
    
    if (!serverScript) {
      reject(new Error('Server script not found'));
      return;
    }
    
    // 设置端口环境变量
    process.env.PORT = SERVER_PORT.toString();
    
    try {
      // 运行服务器脚本
      require(serverScript);
      console.log('[Server] Server module loaded');
      
      // 等待服务器启动
      const checkServer = () => {
        const http = require('http');
        http.get(`http://localhost:${SERVER_PORT}`, (res) => {
          console.log('[Server] Server is running');
          resolve();
        }).on('error', () => {
          setTimeout(checkServer, 200);
        });
      };
      
      setTimeout(checkServer, 500);
      
      // 超时处理
      setTimeout(() => {
        console.log('[Server] Timeout waiting for server, continuing anyway');
        resolve();
      }, 5000);
    } catch (e) {
      console.error('[Server] Failed to start server:', e);
      reject(e);
    }
  });
}

app.whenReady().then(async () => {
  try {
    await startServer();
    console.log('Server started successfully');
  } catch (e) {
    console.error('Failed to start server:', e);
  }

  createWindow();
  createTray();

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      createWindow();
    }
  });
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') {
    app.quit();
  }
});

process.on('uncaughtException', (err) => {
  console.error('Uncaught exception:', err);
});
