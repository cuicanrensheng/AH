package com.tv.live.util;

/**
 * 整数回调接口，替代 java.util.function.Consumer<Integer>
 * 兼容 Android 5.0+ (API 21)，避免 NoClassDefFoundError
 */
public interface IntCallback {
    void accept(int value);
}
