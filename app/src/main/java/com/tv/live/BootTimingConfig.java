package com.tv.live;

/**
 * 启动时序统一配置中心。
 * <p>
 * 将 {@code MyApplication} / {@code MainActivity} 两处分散写死的 postDelayed 延迟值提取到这里，
 * 两处统一引用，避免修改后一处忘改 → 时序错乱。
 * <p>
 * 单元测试 {@code BootTimingConfigTest} 会校验：
 * <ol>
 *   <li>HUYA_POST_DELAY_MS == 950（严格版本号级约束，故意晚于 Displayed=867ms 让系统统计先跑）</li>
 *   <li>PARSER_POST_DELAY_MS == 1950</li>
 *   <li>PARSER - HUYA >= 900ms（≈1秒空档，让出约 40 帧 Loading 动画）</li>
 *   <li>HUYA >= DISPLAYED_EARLIEST_EXPECTED_MS + 32（给 Displayed 至少留 2 帧运行窗口，不被 Huya 反超堵）</li>
 *   <li>(HUYA + HUYA_EXPECTED_COST_MS + GAP_MIN_REQUIRED_MS) <= PARSER（空档至少500ms）</li>
 * </ol>
 */
public final class BootTimingConfig {

    private BootTimingConfig() {}

    // ============================================================
    // 【核心延迟值】启动时序优化的"唯二"旋钮（950 / 1950）
    //   修改时须同步修改：
    //     1) 此处常量
    //     2) boot_timing_sim.mjs 中 OPTIMAL_CONFIG.huyaDelay / parserDelay
    //     3) boot_timing_sim.test.mjs 中 CONFIG_UNDER_TEST
    //   三项改动后跑：node boot_timing_sim.test.mjs
    // ============================================================

    /** HuyaSDK.init 在 Application 里的 postDelayed 延迟（ms） */
    public static final long HUYA_POST_DELAY_MS = 950L;

    /** Parser/WebView.init 在 MainActivity 里的 postDelayed 延迟（ms） */
    public static final long PARSER_POST_DELAY_MS = 1950L;

    // ============================================================
    // 【基线值】真实测量得到，不应轻易改动（测试用来校验时序约束）
    // ============================================================

    /** 雷电14 Release 65秒实测：HuyaSDK.init 主线程同步耗时 ≈ 560ms */
    public static final long HUYA_EXPECTED_COST_MS = 560L;

    /** 雷电14 Release 65秒实测：Parser/WebView.init 主线程同步耗时 ≈ 279ms */
    public static final long PARSER_EXPECTED_COST_MS = 279L;

    /** MainActivity.onCreate 里，onCreate 结束到 ActivityTaskManager: Displayed 事件打印的典型间隔（ms） */
    public static final long DISPLAYED_AFTER_ONCREATE_END_MS = 490L;

    /** Displayed 事件最早可执行时间点基线（onCreate end ≈ 377ms + 490ms = 867ms） */
    public static final long DISPLAYED_EARLIEST_EXPECTED_MS = 867L;

    /** 【强制约束】Huya→Parser 之间的最小空档（ms）：≥500ms 才能让 Loading 动画≥30帧 */
    public static final long GAP_MIN_REQUIRED_MS = 500L;

    /** 【强制约束】PARSER - HUYA 至少要差多少 ms（≥900 保证约1秒间隔，含虎牙560ms后的余量） */
    public static final long MIN_TASK_SPACING_MS = 900L;
}
