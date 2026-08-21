# =============================================================
# TVLive 反调试 / Anti-Frida / AES 解密 - 原生层
# =============================================================
cmake_minimum_required(VERSION 3.22.1)
project(tvlive_security C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 关闭 stack-protector / fortify 让逆向更麻烦
add_compile_options(
        -fvisibility=hidden
        -fno-rtti
        -fno-exceptions
        -fno-stack-protector
        -fno-unwind-tables
        -fno-asynchronous-unwind-tables
        -ffunction-sections
        -fdata-sections
        -Os
)
# 不导出无关符号，strip 后体积更小
add_link_options(
        -Wl,--gc-sections
        -Wl,--exclude-libs,ALL
        -Wl,-z,noexecstack
        -Wl,-z,relro
        -Wl,-z,now
)

add_library(tvlive_security SHARED
        security.cpp
        aes.cpp)

# log 库
find_library(log-lib log)
target_link_libraries(tvlive_security ${log-lib})
