'use strict';

const { exec } = require('child_process');
const EventEmitter = require('events');

/**
 * 网络状态监控器
 *
 * 通过 ADB shell 命令定期采集每台设备的网络信息：
 *   - WiFi: 开关、SSID、IP、信号强度(RSSI)、链路速度
 *   - 移动数据: 开关、网络类型(4G/5G/3G)、运营商、信号强度
 *   - 活跃网络: 当前使用的网络类型
 *   - 代理: HTTP 代理设置
 */
class NetworkMonitor extends EventEmitter {
  constructor(adbPath) {
    super();
    this.adbPath = adbPath;
    this.monitoredDevices = new Map();  // serial -> timer
    this.interval = 5000;               // 采集间隔 5 秒
  }

  setAdbPath(path) {
    this.adbPath = path;
  }

  /**
   * 开始监控指定设备
   */
  startMonitoring(serial) {
    if (this.monitoredDevices.has(serial)) return;

    // 立即采集一次
    this.collectNetworkInfo(serial);

    const timer = setInterval(() => {
      this.collectNetworkInfo(serial);
    }, this.interval);

    this.monitoredDevices.set(serial, timer);
    console.log(`[NetworkMonitor] 开始监控网络状态: ${serial}`);
  }

  /**
   * 停止监控指定设备
   */
  stopMonitoring(serial) {
    const timer = this.monitoredDevices.get(serial);
    if (timer) {
      clearInterval(timer);
      this.monitoredDevices.delete(serial);
      console.log(`[NetworkMonitor] 停止监控网络状态: ${serial}`);
    }
  }

  /**
   * 停止全部
   */
  stopAll() {
    for (const [serial, timer] of this.monitoredDevices) {
      clearInterval(timer);
    }
    this.monitoredDevices.clear();
  }

  /**
   * 采集设备网络信息
   */
  async collectNetworkInfo(serial) {
    try {
      const [wifiOn, mobileDataOn, connectivity, wifiDumpsys, ipInfo, carrier, netType, proxy] = await Promise.all([
        this.shell(serial, 'settings get global wifi_on'),
        this.shell(serial, 'settings get global mobile_data'),
        this.shell(serial, 'dumpsys connectivity'),
        this.shell(serial, 'dumpsys wifi'),
        this.shell(serial, 'ip addr show wlan0 2>/dev/null || ifconfig wlan0 2>/dev/null'),
        this.shell(serial, 'getprop gsm.operator.alpha'),
        this.shell(serial, 'getprop gsm.network.type'),
        this.shell(serial, 'settings get global http_proxy'),
      ]);

      const network = {
        serial,
        timestamp: new Date().toISOString(),
        wifi: this.parseWifi(wifiOn, wifiDumpsys, ipInfo),
        mobile: this.parseMobile(mobileDataOn, carrier, netType),
        proxy: this.parseProxy(proxy),
        activeNetwork: this.parseActiveNetwork(connectivity),
      };

      this.emit('networkUpdate', { serial, network });
    } catch (err) {
      // 静默失败，设备可能已断开
    }
  }

  /**
   * 执行 adb shell 命令
   */
  shell(serial, cmd) {
    return new Promise((resolve) => {
      exec(`"${this.adbPath}" -s ${serial} shell ${cmd}`, { timeout: 8000 }, (err, stdout) => {
        resolve(err ? '' : stdout.trim());
      });
    });
  }

  /**
   * 解析 WiFi 信息
   */
  parseWifi(wifiOn, wifiDumpsys, ipInfo) {
    const wifi = {
      enabled: wifiOn === '1',
      ssid: null,
      ip: null,
      rssi: null,
      linkSpeed: null,
      frequency: null,
    };

    if (!wifi.enabled) return wifi;

    // 从 dumpsys wifi 解析 SSID
    // 格式: "current SSID: xxx" 或 "mWifiInfo SSID: xxx"
    const ssidMatch = wifiDumpsys.match(/SSID:\s*"?([^"\n]+?)"?\s*$/m) ||
                      wifiDumpsys.match(/current SSID:\s*"?([^"\n]+?)"?/i);
    if (ssidMatch && ssidMatch[1] !== '<unknown ssid>') {
      wifi.ssid = ssidMatch[1].trim();
    }

    // 解析 RSSI
    const rssiMatch = wifiDumpsys.match(/RSSI:\s*(-?\d+)/i) ||
                      wifiDumpsys.match(/mRssi:\s*(-?\d+)/i);
    if (rssiMatch) {
      wifi.rssi = parseInt(rssiMatch[1], 10);
    }

    // 解析链路速度
    const speedMatch = wifiDumpsys.match(/Link speed:\s*(\d+)\s*Mbps/i) ||
                       wifiDumpsys.match(/mLinkSpeed:\s*(\d+)/i);
    if (speedMatch) {
      wifi.linkSpeed = parseInt(speedMatch[1], 10);
    }

    // 解析频率
    const freqMatch = wifiDumpsys.match(/Frequency:\s*(\d+)\s*MHz/i);
    if (freqMatch) {
      wifi.frequency = parseInt(freqMatch[1], 10);
    }

    // 解析 IP
    const ipMatch = ipInfo.match(/inet\s+(\d+\.\d+\.\d+\.\d+)/);
    if (ipMatch) {
      wifi.ip = ipMatch[1];
    }

    return wifi;
  }

  /**
   * 解析移动数据信息
   */
  parseMobile(mobileDataOn, carrier, netType) {
    const mobile = {
      enabled: mobileDataOn === '1',
      carrier: carrier || null,
      networkType: this.mapNetworkType(netType),
      rawNetworkType: netType || null,
      signalStrength: null,
    };

    return mobile;
  }

  /**
   * 网络类型映射
   */
  mapNetworkType(rawType) {
    if (!rawType) return null;
    const types = rawType.split(',');
    const labels = [];
    for (const t of types) {
      const num = parseInt(t.trim(), 10);
      const map = {
        0: 'UNKNOWN', 1: 'GPRS', 2: 'EDGE', 3: 'UMTS',
        4: 'CDMA', 5: 'EVDO_0', 6: 'EVDO_A', 7: '1xRTT',
        8: 'HSDPA', 9: 'HSUPA', 10: 'HSPA', 11: 'IDEN',
        12: 'EVDO_B', 13: 'LTE', 14: 'EHRPD', 15: 'HSPAP',
        16: 'GSM', 17: 'TD_SCDMA', 18: 'IWLAN', 19: 'LTE_CA',
        20: 'NR(5G)',
      };
      labels.push(map[num] || `Type(${num})`);
    }
    return labels.join('/') || null;
  }

  /**
   * 解析代理
   */
  parseProxy(proxy) {
    if (!proxy || proxy === 'null' || proxy === ':0') return null;
    return proxy;
  }

  /**
   * 解析活跃网络
   */
  parseActiveNetwork(connectivity) {
    const active = {
      type: null,       // WIFI / MOBILE / NONE
      transport: null,  // wifi / cellular
      hasInternet: false,
    };

    // 查找 "Active default network" 块
    const activeMatch = connectivity.match(/Active default network.*?\n([\s\S]*?)(?=\n\n|\nActive|$)/i);
    if (activeMatch) {
      const block = activeMatch[1];

      if (/transport:\s*WIFI/i.test(block) || /type:\s*WIFI/i.test(block)) {
        active.type = 'WIFI';
        active.transport = 'wifi';
      } else if (/transport:\s*CELLULAR/i.test(block) || /type:\s*MOBILE/i.test(block)) {
        active.type = 'MOBILE';
        active.transport = 'cellular';
      }

      if (/Capabilities:\s*INTERNET/i.test(block) || /VALIDATED/i.test(block)) {
        active.hasInternet = true;
      }
    }

    // 旧版 API 兼容
    if (!active.type) {
      if (/NetworkInfo.*WIFI.*CONNECTED/i.test(connectivity)) {
        active.type = 'WIFI';
        active.transport = 'wifi';
        active.hasInternet = true;
      } else if (/NetworkInfo.*MOBILE.*CONNECTED/i.test(connectivity)) {
        active.type = 'MOBILE';
        active.transport = 'cellular';
        active.hasInternet = true;
      }
    }

    return active;
  }
}

module.exports = NetworkMonitor;
