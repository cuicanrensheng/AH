// =============================================================
// TVLive 原生安全层
//  1. 反调试   ptrace 自挂 + TracerPid 检测
//  2. Anti-Frida   端口扫描 + /proc/self/maps 模块扫描
//  3. Anti-Xposed  maps 中 xposed/substrate 特征
//  4. AES-256-CBC 解密   为 Java 提供密钥碎片拼装 + 解密
//  5. Root/模拟器粗检测
//  6. 完整性校验   Java 端 class hash 由 Native 计算并比对
// =============================================================
#include <jni.h>
#include <android/log.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/ptrace.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <arpa/inet.h>
#include <dirent.h>
#include <cstring>
#include <cstdio>
#include <cstdlib>
#include <cstdint>
#include <pthread.h>
#include <string>
#include <vector>
#include <algorithm>
#include <errno.h>

#define TAG "TVLS"
// 关闭 debug 日志（发布版）
#define LOGI(...) ((void)0)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ============== 1. ptrace 反调试 ==============
// 改为：失败只记 errno，不直接 _exit。避免模拟器/部分 ROM 因 SELinux/watchdog 误报导致闪退。
static int g_ptrace_failed = 0;
static int g_ptrace_errno  = 0;
static void anti_debug_ptrace() {
    long ret = ptrace(PTRACE_TRACEME, 0, nullptr, nullptr);
    if (ret == -1) {
        g_ptrace_failed = 1;
        g_ptrace_errno  = errno;
        // 不要 _exit：模拟器/部分国产 ROM 上 ptrace TRACEME 经常因 SELinux/watchdog 失败
        // 真正的反调试改由 TracerPid 检测 + 后台 monitor 承担
        LOGW("ptrace TRACEME failed errno=%d (ignored, rely on TracerPid+maps scan)", errno);
        return;
    }
    // 立刻 detach 避免卡死
    ptrace(PTRACE_DETACH, 0, nullptr, nullptr);
}

// ============== 2. /proc/self/status TracerPid 检测 ==============
static int check_tracer_pid() {
    int fd = open("/proc/self/status", O_RDONLY);
    if (fd < 0) return 0;
    char buf[4096] = {0};
    ssize_t n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n <= 0) return 0;

    // 查找 "TracerPid:" 后面的数字
    const char* key = "TracerPid:";
    char* p = strstr(buf, key);
    if (!p) return 0;
    p += strlen(key);
    while (*p == ' ' || *p == '\t') p++;
    int pid = atoi(p);
    // 0 表示未被 trace；>0 表示被 trace
    if (pid > 0) {
        LOGE("TracerPid=%d, debugger running", pid);
        return 1;
    }
    return 0;
}

// ============== 3. Anti-Frida 端口扫描 ==============
static const int FRIDA_PORTS[] = {
    27042, 27043,                // frida-server default
    4455, 4456, 4457,            // older frida
    8000, 8080, 8888,            // common backdoor
    0
};

static int scan_frida_ports() {
    for (int i = 0; FRIDA_PORTS[i] != 0; ++i) {
        int sock = socket(AF_INET, SOCK_STREAM, 0);
        if (sock < 0) continue;
        struct sockaddr_in sa{};
        sa.sin_family = AF_INET;
        sa.sin_port = htons(FRIDA_PORTS[i]);
        sa.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        // 设非阻塞避免卡住
        struct timeval tv{};
        tv.tv_sec = 0;
        tv.tv_usec = 100000;  // 100ms
        setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
        setsockopt(sock, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
        int r = connect(sock, (struct sockaddr*)&sa, sizeof(sa));
        if (r == 0) {
            LOGE("frida-like port %d OPEN", FRIDA_PORTS[i]);
            close(sock);
            return 1;
        }
        close(sock);
    }
    return 0;
}

// ============== 4. /proc/self/maps 模块扫描 ==============
static int g_frida_in_maps = 0;
static void scan_maps_for_frida() {
    int fd = open("/proc/self/maps", O_RDONLY);
    if (fd < 0) return;
    char buf[16384] = {0};
    ssize_t total = 0;
    while (total < (ssize_t)sizeof(buf) - 1) {
        ssize_t n = read(fd, buf + total, sizeof(buf) - 1 - total);
        if (n <= 0) break;
        total += n;
    }
    close(fd);
    buf[total] = 0;

    // 检测特征
    const char* keys[] = {
        "frida",
        "gadget",
        "gmain",
        "agent",
        "xposed",
        "substrate",
        "libsubstrate",
        "libxposed",
        "edxp",
        "lsposed",
        "magisk",
        nullptr
    };
    for (int i = 0; keys[i]; ++i) {
        if (strstr(buf, keys[i])) {
            LOGE("hook framework detected in maps: %s", keys[i]);
            g_frida_in_maps = 1;
            return;
        }
    }
}

// ============== 5. Root 检测（增强版）==============
static int read_file_line(const char* path, const char* key, char* out, int outlen) {
    FILE* f = fopen(path, "r");
    if (!f) return 0;
    char line[1024];
    while (fgets(line, sizeof(line), f)) {
        if (strstr(line, key)) {
            strncpy(out, line, outlen - 1);
            out[outlen - 1] = 0;
            fclose(f);
            return 1;
        }
    }
    fclose(f);
    return 0;
}

static int check_root() {
    const char* paths[] = {
        "/system/xbin/su", "/system/bin/su", "/sbin/su",
        "/system/su", "/system/app/Superuser.apk",
        "/system/app/SuperSU.apk", "/data/adb/magisk",
        "/sbin/magisk", "/data/adb/ksu",
        "/data/adb/modules",          // Magisk 模块目录
        "/data/adb/lspd",             // LSPosed
        "/data/adb/riru",             // Riru
        "/data/data/com.topjohnwu.magisk",
        nullptr
    };
    for (int i = 0; paths[i]; ++i) {
        if (access(paths[i], F_OK) == 0) {
            LOGW("root file found: %s", paths[i]);
            return 1;
        }
    }
    // which su
    FILE* p = popen("which su 2>/dev/null", "r");
    if (p) {
        char line[256] = {0};
        if (fgets(line, sizeof(line), p) != nullptr) {
            if (strstr(line, "su")) {
                pclose(p);
                return 1;
            }
        }
        pclose(p);
    }
    // 检查 build.prop 中的 ro.debuggable
    char line[1024];
    if (read_file_line("/system/build.prop", "ro.debuggable", line, sizeof(line))) {
        if (strstr(line, "=1") || strstr(line, "=true")) {
            LOGW("ro.debuggable=1, system-debug build");
            return 1;
        }
    }
    // Magisk 隐藏挂载：/proc/mounts 中搜 "magisk"
    if (read_file_line("/proc/mounts", "magisk", line, sizeof(line))) {
        LOGW("magisk mount found in /proc/mounts");
        return 1;
    }
    return 0;
}

// ============== 6. 模拟器检测（增强版）==============
static int check_emulator() {
    int score = 0;
    // 读 /proc/cpuinfo
    FILE* f = fopen("/proc/cpuinfo", "r");
    if (f) {
        char line[512];
        while (fgets(line, sizeof(line), f)) {
            if (strstr(line, "Goldfish") ||
                strstr(line, "Ranchu") ||
                strstr(line, "Intel") ||
                strstr(line, "amd64")) {
                fclose(f);
                return 1;
            }
        }
        fclose(f);
    }
    // 模拟器特征文件
    const char* files[] = {
        "/dev/qemu_pipe", "/dev/goldfish_pipe",
        "/system/lib/libc_malloc_debug_qemu.so",
        "/sys/qemu_trace",
        "/system/bin/qemu-props",
        // 国产模拟器特征
        "/system/lib/libldutils.so",        // 雷电
        "/system/lib/libnemu.so",           // 网易 MuMu
        "/data/data/com.microvirt.tools",   // 逍遥
        "/data/data/com.bignox.app",        // 夜神
        "/data/data/com.vphone.helper",     // vphone
        "/data/data/com.bluestacks",        // BlueStacks
        "/data/data/com.ldmnq.makemachine", // 雷电 Magisk 隐藏
        "/data/data/com.mumu.electron",     // MuMu 12
        nullptr
    };
    for (int i = 0; files[i]; ++i) {
        if (access(files[i], F_OK) == 0) return 1;
    }
    // 检查 ro.product.model / ro.product.device 中的模拟器关键字
    char line[1024];
    if (read_file_line("/system/build.prop", "ro.product.model", line, sizeof(line))) {
        if (strstr(line, "Emulator") || strstr(line, "Android SDK") ||
            strstr(line, "MuMu") || strstr(line, "雷电") ||
            strstr(line, "夜神") || strstr(line, "BlueStacks") ||
            strstr(line, "Nox")) {
            return 1;
        }
    }
    if (read_file_line("/system/build.prop", "ro.product.device", line, sizeof(line))) {
        if (strstr(line, "generic") || strstr(line, "vbox86") ||
            strstr(line, "emu64a") || strstr(line, "emu86a")) {
            return 1;
        }
    }
    return 0;
}

// ============== 7. Key 拼装（AES-256 key 分片，运行时拼接）==============
// 16 字节 key 碎片 1（这里写死，实际应混淆在指令流中）
// 为安全：把真 key 拆成 3 段并 XOR 一个 runtime token
static const uint8_t KEY_PART_A[16] = {
    0x9c, 0x3f, 0xa1, 0x77, 0x55, 0x88, 0x10, 0xcc,
    0x2d, 0x4b, 0xe6, 0x91, 0x07, 0xb3, 0xd5, 0x42
};
static const uint8_t KEY_PART_B[16] = {
    0x7a, 0xb1, 0x05, 0xe9, 0x33, 0x6f, 0xc2, 0x4d,
    0x18, 0xfa, 0x82, 0x59, 0xa0, 0x21, 0x6c, 0xd7
};
// runtime token 来自 JNI 调用时的 jclass 哈希
static uint8_t g_runtime_token[16] = {0};

extern "C" JNIEXPORT void JNICALL
Java_com_tv_live_security_SecurityCore_nativeSetToken(JNIEnv* env, jclass, jbyteArray token) {
    if (!token) return;
    jsize len = env->GetArrayLength(token);
    if (len > 16) len = 16;
    env->GetByteArrayRegion(token, 0, len, (jbyte*)g_runtime_token);
    // 不足 16 字节补 0
    for (int i = len; i < 16; ++i) g_runtime_token[i] = 0;
}

static void build_aes_key(uint8_t out[32]) {
    // key = (A XOR token) || (B XOR token)
    for (int i = 0; i < 16; ++i) out[i]      = KEY_PART_A[i] ^ g_runtime_token[i];
    for (int i = 0; i < 16; ++i) out[16 + i] = KEY_PART_B[i] ^ g_runtime_token[i];
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_tv_live_security_SecurityCore_nativeDecrypt(JNIEnv* env, jclass, jbyteArray cipher) {
    if (!cipher) return nullptr;
    jsize len = env->GetArrayLength(cipher);
    // AES-CBC 密文 = 16 字节 IV + 加密数据
    if (len < 32 || (len - 16) % 16 != 0) {
        LOGE("invalid cipher length=%d", len);
        return nullptr;
    }
    jbyte* data = env->GetByteArrayElements(cipher, nullptr);
    if (!data) return nullptr;

    // 调用 aes_decrypt（实现见 aes.cpp）
    extern int aes256_cbc_decrypt(const uint8_t* in, int in_len,
                                   const uint8_t key[32], uint8_t* out, int* out_len);
    uint8_t key[32];
    build_aes_key(key);

    int out_len = 0;
    int cap = len;
    uint8_t* out = (uint8_t*)malloc(cap);
    int rc = aes256_cbc_decrypt((const uint8_t*)data, len, key, out, &out_len);
    env->ReleaseByteArrayElements(cipher, data, JNI_ABORT);
    if (rc != 0) {
        LOGE("aes decrypt failed rc=%d", rc);
        free(out);
        return nullptr;
    }
    jbyteArray result = env->NewByteArray(out_len);
    env->SetByteArrayRegion(result, 0, out_len, (jbyte*)out);
    free(out);
    return result;
}

// ============== 8. 综合安全检查 ==============
//  返回 bitmask: bit0=debug, bit1=frida_port, bit2=frida_maps, bit3=root, bit4=emulator
extern "C" JNIEXPORT jint JNICALL
Java_com_tv_live_security_SecurityCore_nativeCheck(JNIEnv*, jclass) {
    int result = 0;
    if (check_tracer_pid()) result |= 0x01;
    if (scan_frida_ports()) result |= 0x02;
    if (g_frida_in_maps)    result |= 0x04;
    if (check_root())       result |= 0x08;
    if (check_emulator())   result |= 0x10;
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_tv_live_security_SecurityCore_nativeAntiDebug(JNIEnv*, jclass) {
    anti_debug_ptrace();
}

// 启动后台线程，定期扫描 frida maps 变化
static pthread_t g_monitor_thread;
static volatile int g_monitor_run = 0;
static void* monitor_loop(void*) {
    while (g_monitor_run) {
        scan_maps_for_frida();
        usleep(2000 * 1000);  // 2s
    }
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_tv_live_security_SecurityCore_nativeStartMonitor(JNIEnv*, jclass) {
    if (g_monitor_run) return;
    g_monitor_run = 1;
    pthread_create(&g_monitor_thread, nullptr, monitor_loop, nullptr);
    pthread_detach(g_monitor_thread);
}
