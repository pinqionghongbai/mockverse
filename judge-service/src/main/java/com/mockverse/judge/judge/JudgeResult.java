package com.mockverse.judge.judge;

/**
 * 一次判题的客观结果（D18）。
 *
 * <p>字段全部是**可观察事实**，没有"评分/结论"——那属于面试官 Agent 的判断。
 * {@code compiled=false} 时 {@code ran} 必为 false；{@code timedOut=true} 时
 * {@code exitCode} 不可信（进程是被杀的）。
 *
 * @param language        语言标识；当前只支持 {@code java}
 * @param className       从代码里识别出的主类名（写文件名与启动都用它）
 * @param compiled        编译是否通过
 * @param compileOutput   编译期诊断（javac 的 stderr/stdout 合并），已截断
 * @param ran             是否真的运行了（编译失败 / 参数非法时为 false）
 * @param timedOut        是否超时被杀
 * @param exitCode        进程退出码；超时或未运行时为 null
 * @param stdout          标准输出，已截断
 * @param stderr          标准错误，已截断
 * @param durationMs      编译 + 运行的墙钟耗时
 * @param expectedOutput  调用方给的期望输出（原样回显，便于模型对照）
 * @param outputMatched   与期望输出是否一致；未给期望输出时为 null
 * @param error           判题服务自身的问题（参数非法等），非 null 时其余字段不可信
 */
public record JudgeResult(
        String language,
        String className,
        boolean compiled,
        String compileOutput,
        boolean ran,
        boolean timedOut,
        Integer exitCode,
        String stdout,
        String stderr,
        long durationMs,
        String expectedOutput,
        Boolean outputMatched,
        String error) {

    /** 参数/环境问题：什么都没跑起来。 */
    static JudgeResult rejected(String reason) {
        return new JudgeResult("java", null, false, "", false, false, null,
                "", "", 0L, null, null, reason);
    }
}
