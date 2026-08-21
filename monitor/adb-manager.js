'use strict';

const { spawn, exec } = require('child_process');
const EventEmitter = require('events');

/**
 * ADB 设备管理器
 *
 * 负责管理多台 Android 设备的 ADB 连接，
 * 为每台设备启动独立的 logcat 进程，流式读取日志。
 */
class AdbManager extends EventEmitter {
  constructor() {
    super();
    this.devices = new Map();       // serial -> deviceInfo
    this.logcatProcs = new Map();   // serial -> ChildProcess
    this.scanTimer = null;
    this.packageName = 'com.tv.live';
  }

  /**
   * 查找系统中的 adb 可执行文件路径
   */
  findAdb() {
    return new Promise((resolve) => {
      // 常见路径
      const candidates = [
        process.env.ANDROID_HOME ? `${process.env.ANDROID_HOME}\\platform-tools\\adb.exe` : null,
        process.env.ANDROID_SDK_ROOT ? `${process.env.ANDROID_SDK_ROOT}\\platform-tools\\adb.exe` : null,
        `${process.env.LOCALAPPDATA}\\Android\\Sdk\\platform-tools\\adb.exe`,
        'C:\\Android\\Sdk\\platform-tools\\adb.exe',
        'D:\\Android\\Sdk\\platform-tools\\adb.exe',
        'adb', // 依赖 PATH
      ].filter(Boolean);

      const tryNext = (idx) => {
        if (idx >= candidates.length) {
          resolve('adb');
          return;
        }
        exec(`"${candidates[idx]}" version`, (err) => {
          if (!err) {
            resolve(candidates[idx]);
          } else {
            tryNext(idx + 1);
          }
        });
      };
      tryNext(0);
    });
  }

  /**
   * 启动设备扫描
   */
  async start() {
    this.adbPath = await this.findAdb();
    console.log(`[AdbManager] 使用 ADB: ${this.adbPath}`);
    await this.scanDevices();
    this.scanTimer = setInterval(() => this.scanDevices(), 3000);
  }

  /**
   * 停止所有
   */
  stop() {
    if (this.scanTimer) {
      clearInterval(this.scanTimer);
      this.scanTimer = null;
    }
    for (const [serial] of this.logcatProcs) {
      this.stopLogcat(serial);
    }
    this.devices.clear();
  }

  /**
   * 扫描在线设备
   */
  async scanDevices() {
    return new Promise((resolve) => {
      exec(`"${this.adbPath}" devices -l`, (err, stdout) => {
        if (err) {
          this.emit('error', `ADB devices 扫描失败: ${err.message}`);
          resolve();
          return;
        }

        const lines = stdout.split('\n').slice(1); // 跳过 "List of devices attached"
        const currentSerials = new Set();

        for (const line of lines) {
          const trimmed = line.trim();
          if (!trimmed) continue;

          const parts = trimmed.split(/\s+/);
          const serial = parts[0];
          const state = parts[1];

          if (!serial || serial === '*') continue;
          currentSerials.add(serial);

          // 解析设备信息
          const info = { serial, state };
          for (let i = 2; i < parts.length; i++) {
            const [key, val] = parts[i].split(':');
            if (key && val) info[key] = val;
          }

          // 获取设备型号
          if (state === 'device' && !this.devices.has(serial)) {
            this.devices.set(serial, info);
            this.emit('deviceConnected', info);
            this.startLogcat(serial);
            this.getDeviceProps(serial).then(props => {
              info.props = props;
              this.emit('deviceUpdated', info);
            });
          } else if (state !== 'device' && this.devices.has(serial)) {
            // 设备离线
            this.stopLogcat(serial);
            this.devices.delete(serial);
            this.emit('deviceDisconnected', { serial, state });
          }
        }

        // 检查已消失的设备
        for (const [serial] of this.devices) {
          if (!currentSerials.has(serial)) {
            this.stopLogcat(serial);
            this.devices.delete(serial);
            this.emit('deviceDisconnected', { serial, state: 'gone' });
          }
        }

        resolve();
      });
    });
  }

  /**
   * 获取设备属性
   */
  getDeviceProps(serial) {
    return new Promise((resolve) => {
      const props = {};
      const commands = [
        `ro.product.model`,
        `ro.product.brand`,
        `ro.build.version.release`,
        `ro.product.cpu.abi`,
      ];

      let done = 0;
      const checkDone = () => {
        done++;
        if (done >= commands.length) {
          resolve(props);
        }
      };

      for (const prop of commands) {
        exec(`"${this.adbPath}" -s ${serial} shell getprop ${prop}`, (err, stdout) => {
          if (!err) {
            const key = prop.split('.').pop();
            props[key] = stdout.trim();
          }
          checkDone();
        });
      }
    });
  }

  /**
   * 启动 logcat 流式读取
   */
  startLogcat(serial) {
    if (this.logcatProcs.has(serial)) return;

    // 使用 --pid 过滤指定应用，先获取 PID
    exec(`"${this.adbPath}" -s ${serial} shell pidof ${this.packageName}`, (err, stdout) => {
      const pid = stdout.trim();

      if (pid) {
        // 有 PID，按 PID 过滤
        this._spawnLogcat(serial, ['--pid=' + pid]);
      } else {
        // 应用未运行，监听所有日志但按包名过滤
        // 使用 logcat -s 只输出指定 TAG
        this._spawnLogcat(serial, ['-v', 'threadtime']);
      }
    });
  }

  /**
   * 实际 spawn logcat 进程
   */
  _spawnLogcat(serial, args) {
    const proc = spawn(this.adbPath, ['-s', serial, 'logcat', '-v', 'threadtime', ...args], {
      windowsHide: true,
    });

    this.logcatProcs.set(serial, proc);

    proc.stdout.on('data', (data) => {
      const lines = data.toString().split('\n');
      for (const line of lines) {
        if (line.trim()) {
          this.emit('log', { serial, raw: line });
        }
      }
    });

    proc.stderr.on('data', (data) => {
      // logcat stderr 通常是设备断开等错误
      const msg = data.toString().trim();
      if (msg) {
        this.emit('logError', { serial, error: msg });
      }
    });

    proc.on('exit', (code) => {
      console.log(`[AdbManager] logcat(${serial}) 退出, code=${code}`);
      this.logcatProcs.delete(serial);
      // 如果设备还在线，5秒后重试
      if (this.devices.has(serial)) {
        setTimeout(() => {
          if (this.devices.has(serial) && !this.logcatProcs.has(serial)) {
            console.log(`[AdbManager] 重新连接 logcat(${serial})`);
            this.startLogcat(serial);
          }
        }, 5000);
      }
    });
  }

  /**
   * 停止某设备的 logcat
   */
  stopLogcat(serial) {
    const proc = this.logcatProcs.get(serial);
    if (proc) {
      try {
        proc.kill('SIGKILL');
      } catch (e) {
        // 忽略
      }
      this.logcatProcs.delete(serial);
    }
  }

  /**
   * 获取所有设备列表
   */
  getDevices() {
    return Array.from(this.devices.values());
  }
}

module.exports = AdbManager;
