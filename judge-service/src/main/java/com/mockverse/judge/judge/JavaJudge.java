package com.mockverse.judge.judge;

import com.mockverse.judge.config.JudgeProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Java 代码判题：真的编译、真的运行（D18）。
 *
 * <p><b>为什么不用单文件源码启动（{@code java Foo.java}）</b>：那样编译错误会和运行输出
 * 混在同一条流里，判题分不清"没编译过"和"跑崩了"——而这正是面试官最需要区分的两件事。
 * 显式两步（javac → java）能让 {@code compiled} / {@code ran} 成为独立的事实。
 *
 * <p><b>输入是不可信代码</b>，所以每一步都有上限：工作目录独立、内存上限、编译/运行超时、
 * 输出截断（读走丢弃而不是只读到上限就停——停读会让子进程写管道阻塞，假死）、并发上限。
 * <b>这不是容器级沙盒</b>：代码仍能碰宿主文件系统与网络，见 {@link JudgeProperties} 的类注释。
 */
@Slf4j
@Component
public class JavaJudge {

    /** 优先找 {@code public} 的类型：文件名必须与 public 类同名。 */
    private static final Pattern PUBLIC_TYPE = Pattern.compile(
            "\\bpublic\\s+(?:(?:final|abstract|sealed|non-sealed|strictfp)\\s+)*"
                    + "(?:class|interface|enum|record)\\s+(\\w+)");

    /** 所有类型声明（含非 public），带位置——用来找 main 方法落在哪个类型里。 */
    private static final Pattern TYPE_DECL = Pattern.compile(
            "\\b(?:class|interface|enum|record)\\s+(\\w+)");

    /** 入口方法声明。 */
    private static final Pattern MAIN_METHOD = Pattern.compile("\\bstatic\\s+void\\s+main\\s*\\(");

    private final JudgeProperties properties;

    /** 判题是 CPU + 进程密集型，并发必须封顶。公平模式：先到先服务，避免大请求饿死。 */
    private final Semaphore permits;

    public JavaJudge(JudgeProperties properties) {
        this.properties = properties;
        this.permits = new Semaphore(Math.max(1, properties.getMaxConcurrent()), true);
    }

    /**
     * 判一次代码。
     *
     * @param code           候选人提交的 Java 源码
     * @param stdin          运行期喂给程序的输入；可为 null
     * @param expectedOutput 期望的 stdout；为 null 表示不比对
     * @param timeoutSeconds 运行超时（秒）；null 用默认值，超过上限会被夹到上限
     */
    public JudgeResult judge(String code, String stdin, String expectedOutput,
                             Integer timeoutSeconds) {
        if (code == null || code.isBlank()) {
            return JudgeResult.rejected("code 为空：没有可判题的代码");
        }
        if (code.length() > properties.getMaxCodeLength()) {
            return JudgeResult.rejected("code 过长（" + code.length() + " 字符，上限 "
                    + properties.getMaxCodeLength() + "）");
        }

        // 文件名与启动类是两个不同的问题：javac 要求文件名 == public 类型名，
        // 而 java 要跑的是**含 main 的那个类型**——候选人常把辅助类写在前、主类写在后
        String fileName = detectPublicType(code);
        String runClass = detectMainClass(code);
        if (runClass == null) {
            runClass = fileName == null ? "Main" : fileName;
        }
        if (fileName == null) {
            fileName = runClass;
        }
        Duration runTimeout = resolveRunTimeout(timeoutSeconds);

        boolean acquired = false;
        Path workDir = null;
        long start = System.nanoTime();
        try {
            acquired = permits.tryAcquire(30, TimeUnit.SECONDS);
            if (!acquired) {
                return JudgeResult.rejected("判题服务繁忙（已有 " + permits.getQueueLength()
                        + " 个请求排队），请稍后重试");
            }

            workDir = createWorkDir();
            Path source = workDir.resolve(fileName + ".java");
            Files.writeString(source, code, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            // ---- 编译 ----
            // -J 把选项传给 javac 自己的 JVM：`-encoding UTF-8` 只影响**读源码**，javac 的
            // 诊断输出走的是它 JVM 的 stderr 编码（Windows 默认 GBK），不钉死会让中文编译错误
            // 在判题结果里变成替换字符（D18 实测）
            List<String> compileCmd = List.of(
                    tool("javac"),
                    "-J-Dfile.encoding=UTF-8",
                    "-J-Dstdout.encoding=UTF-8",
                    "-J-Dstderr.encoding=UTF-8",
                    "-encoding", "UTF-8",
                    "-d", workDir.toString(),
                    source.toString());
            Proc compile = exec(compileCmd, workDir, null, properties.getCompileTimeout());
            if (compile.timedOut()) {
                return new JudgeResult("java", runClass, false,
                        "编译超时（" + properties.getCompileTimeout().toSeconds() + "s）",
                        false, true, null, "", "", elapsedMs(start), expectedOutput, null, null);
            }
            if (compile.exitCode() != 0) {
                String diagnostics = (compile.stdout() + compile.stderr()).trim();
                return new JudgeResult("java", runClass, false,
                        diagnostics.isBlank() ? "编译失败（javac 退出码 " + compile.exitCode()
                                + "，无诊断输出）" : diagnostics,
                        false, false, compile.exitCode(), "", "",
                        elapsedMs(start), expectedOutput, null, null);
            }

            // ---- 运行 ----
            List<String> runCmd = new ArrayList<>(List.of(
                    tool("java"),
                    "-Xmx" + properties.getMaxHeapMb() + "m",
                    // 短命程序：跳过 C2 编译，冷启动快一倍；对判题这类秒级任务没有副作用
                    "-XX:TieredStopAtLevel=1",
                    "-XX:+UseSerialGC",
                    // Java 18+ 在 Windows 上默认按控制台编码输出；显式钉死 UTF-8，
                    // 否则中文答案在判题结果里会变成乱码
                    "-Dfile.encoding=UTF-8",
                    "-Dstdout.encoding=UTF-8",
                    "-Dstderr.encoding=UTF-8",
                    "-cp", workDir.toString(),
                    runClass));
            Proc run = exec(runCmd, workDir, stdin, runTimeout);

            Boolean matched = expectedOutput == null
                    ? null
                    : normalize(run.stdout()).equals(normalize(expectedOutput));

            return new JudgeResult("java", runClass, true, "",
                    !run.timedOut(), run.timedOut(), run.exitCode(),
                    run.stdout(), run.stderr(), elapsedMs(start),
                    expectedOutput, matched, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return JudgeResult.rejected("判题被中断");
        } catch (IOException e) {
            log.error("判题执行环境异常", e);
            return JudgeResult.rejected("判题执行环境异常: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
        } finally {
            if (acquired) {
                permits.release();
            }
            deleteQuietly(workDir);
        }
    }

    /** 请求里的超时夹在 (0, maxRunTimeout] 区间内；未给则用默认值。 */
    private Duration resolveRunTimeout(Integer timeoutSeconds) {
        Duration max = properties.getMaxRunTimeout();
        if (timeoutSeconds == null || timeoutSeconds <= 0) {
            return properties.getDefaultRunTimeout();
        }
        Duration requested = Duration.ofSeconds(timeoutSeconds);
        return requested.compareTo(max) > 0 ? max : requested;
    }

    /**
     * 文件名该叫什么：{@code public} 类型存在时只能是它（javac 的硬约束），否则返回 null。
     */
    private static String detectPublicType(String code) {
        Matcher pub = PUBLIC_TYPE.matcher(mask(code));
        return pub.find() ? pub.group(1) : null;
    }

    /**
     * 启动类该是哪个：**包住 main 方法的那层类型**。
     *
     * <p><b>为什么不能取"第一个类型"</b>（D18 实测踩到）：候选人常把辅助类写在前、主类写在后，
     * 例如 {@code class Helper {...} class Solution { main }}。取第一个会去跑 {@code Helper}，
     * 得到"找不到 main 方法"——把一段**完全正确**的代码判成了错，而且是很难自查的错。
     *
     * <p><b>为什么也不能取"main 之前最后一个类型"</b>（D18 六查实测踩到，同一族病灶）：
     * {@code class Solution { static class Node {...} public static void main(...) }} 里，
     * main 之前最后一个类型是**嵌套的** {@code Node}，跑它同样"找不到 main 方法"。
     * 候选人为链表/树题写静态内部类是常态，这条路必须走对。
     *
     * <p>做法：先把注释/字符串/字符字面量抹成空格（等长，下标不变），再用花括号栈扫描——
     * 遇到类型声明就记住名字，遇到 {@code {} 就压栈，遇到 {@code }} 就出栈；走到 main 的位置时，
     * 栈顶就是包住它的那个类型。没有 main 时返回 null，由调用方回落到 public 类型/默认名——
     * 反正跑不起来，错误会如实返回。
     *
     * <p>抹掉注释与字符串还顺带解决了"注释里写 {@code class Foo} / {@code main} 被误认"。
     * 仍可能误判的是**文本块里的伪代码**（已按字符串抹掉，不会命中）与 `$` 嵌套类的调用形式
     * （{@code java Outer$Inner}）——后者不在面试题的常见写法里。
     */
    private static String detectMainClass(String code) {
        String masked = mask(code);
        Matcher main = MAIN_METHOD.matcher(masked);
        if (!main.find()) {
            return null;
        }
        int mainAt = main.start();

        Matcher type = TYPE_DECL.matcher(masked);
        boolean hasType = type.find();
        Deque<String> enclosing = new ArrayDeque<>();
        String pending = null;
        for (int i = 0; i < masked.length(); i++) {
            while (hasType && type.start() == i) {
                pending = type.group(1);
                hasType = type.find();
            }
            if (i == mainAt) {
                String name = enclosing.peek();
                return name == null || name.isEmpty() ? null : name;
            }
            char c = masked.charAt(i);
            if (c == '{') {
                // 非类型的花括号（方法体/初始化块/匿名类）压一个空名占位，保证出栈对齐
                enclosing.push(pending == null ? "" : pending);
                pending = null;
            } else if (c == '}' && !enclosing.isEmpty()) {
                enclosing.pop();
            }
        }
        return null;
    }

    /**
     * 把注释、字符串、字符字面量抹成空格，**保持长度与下标不变**。
     *
     * <p>只为让正则与花括号计数看不见它们：注释里的 {@code class}、字符串里的 {@code &#123;}
     * 都会把朴素的文本扫描带偏。换行符保留，行号与文本块边界因此不受影响。
     */
    private static String mask(String code) {
        char[] out = code.toCharArray();
        int n = out.length;
        int i = 0;
        while (i < n) {
            char c = out[i];
            if (c == '/' && i + 1 < n && out[i + 1] == '/') {
                while (i < n && out[i] != '\n') {
                    out[i++] = ' ';
                }
            } else if (c == '/' && i + 1 < n && out[i + 1] == '*') {
                out[i++] = ' ';
                out[i++] = ' ';
                while (i < n && !(out[i] == '*' && i + 1 < n && out[i + 1] == '/')) {
                    out[i++] = ' ';
                }
                if (i < n) {
                    out[i++] = ' ';
                }
                if (i < n) {
                    out[i++] = ' ';
                }
            } else if (c == '"' || c == '\'') {
                boolean textBlock = c == '"' && i + 2 < n && out[i + 1] == '"' && out[i + 2] == '"';
                int quote = textBlock ? 3 : 1;
                for (int k = 0; k < quote && i < n; k++) {
                    out[i++] = ' ';
                }
                while (i < n) {
                    if (out[i] == '\\' && i + 1 < n) {
                        out[i++] = ' ';
                        out[i++] = ' ';
                    } else if (textBlock && out[i] == '"' && i + 2 < n
                            && out[i + 1] == '"' && out[i + 2] == '"') {
                        out[i++] = ' ';
                        out[i++] = ' ';
                        out[i++] = ' ';
                        break;
                    } else if (!textBlock && out[i] == c) {
                        out[i++] = ' ';
                        break;
                    } else {
                        out[i++] = ' ';
                    }
                }
            } else {
                i++;
            }
        }
        return new String(out);
    }

    /** 用当前 JVM 自带的 JDK 工具，不依赖 PATH（服务被守护进程拉起时 PATH 往往没有 java）。 */
    private static String tool(String name) {
        String exe = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? name + ".exe" : name;
        return Path.of(System.getProperty("java.home"), "bin", exe).toString();
    }

    private Path createWorkDir() throws IOException {
        String root = properties.getWorkRoot();
        Path base = root == null || root.isBlank()
                ? Path.of(System.getProperty("java.io.tmpdir"))
                : Path.of(root);
        Files.createDirectories(base);
        return Files.createTempDirectory(base, "judge-");
    }

    // ------------------------------------------------------------------ 进程执行

    /** 一次子进程调用的结果。{@code stdout}/{@code stderr} 都已按上限截断。 */
    private record Proc(int exitCode, String stdout, String stderr, boolean timedOut) {
    }

    /**
     * 跑一个子进程，带超时与输出截断。
     *
     * <p>stdout / stderr 必须**并行读**：只读一路会让另一路的管道缓冲区写满，子进程阻塞在
     * write 上，看起来就像"程序卡死"。截断的做法是继续读但只保留前 N 字节——读到上限就停读
     * 同样会造成阻塞。
     */
    private Proc exec(List<String> command, Path workDir, String stdinText, Duration timeout)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command).directory(workDir.toFile());
        Process process = pb.start();

        int limit = properties.getMaxOutputBytes();
        CompletableFuture<String> out = CompletableFuture.supplyAsync(() -> drain(process.getInputStream(), limit));
        CompletableFuture<String> err = CompletableFuture.supplyAsync(() -> drain(process.getErrorStream(), limit));

        if (stdinText != null && !stdinText.isEmpty()) {
            try (OutputStream os = process.getOutputStream()) {
                // 输入同样按上限截断：程序不读 stdin 时写太多会阻塞在管道上
                byte[] bytes = stdinText.getBytes(StandardCharsets.UTF_8);
                int n = Math.min(bytes.length, limit);
                os.write(bytes, 0, n);
                os.flush();
            } catch (IOException e) {
                // 程序提前退出导致管道关闭是正常情况（它可能不读输入），不算失败
                log.debug("写入子进程 stdin 时管道已关闭: {}", e.getMessage());
            }
        } else {
            closeQuietly(process.getOutputStream());
        }

        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            killTree(process);
            process.waitFor(2, TimeUnit.SECONDS);
        }
        String stdout = out.join();
        String stderr = err.join();
        return new Proc(finished ? process.exitValue() : -1, stdout, stderr, !finished);
    }

    /** 读走整条流但只保留前 {@code limit} 字节，避免"读到上限就停"导致子进程阻塞。 */
    private static String drain(InputStream in, int limit) {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long total = 0;
        try (in) {
            int n;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (head.size() < limit) {
                    head.write(buf, 0, Math.min(n, limit - head.size()));
                }
            }
        } catch (IOException e) {
            // 进程被杀时流会被关闭，这是超时路径的正常现象
            return head.toString(StandardCharsets.UTF_8);
        }
        String text = head.toString(StandardCharsets.UTF_8);
        return total > limit
                ? text + "\n...(输出已截断：共 " + total + " 字节，仅保留前 " + limit + " 字节)"
                : text;
    }

    /** 先杀子孙再杀自己：只杀父进程会留下孤儿 JVM 继续吃 CPU。 */
    private static void killTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private static void closeQuietly(OutputStream os) {
        try {
            os.close();
        } catch (IOException ignored) {
            // 关闭空 stdin 失败不影响判题
        }
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.debug("清理判题临时文件失败: {}", p);
                }
            });
        } catch (IOException e) {
            log.debug("清理判题目录失败: {}", dir);
        }
    }

    private static long elapsedMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    /**
     * 输出比对口径：统一换行符、去掉每行尾随空白、去掉首尾空行。
     *
     * <p>不这么做的话，Windows 的 {@code \r\n} 与程序里 {@code println} 的细微差异会让"逻辑
     * 完全正确"的答案被判错——判题必须对格式噪音免疫，但对内容严格。
     */
    private static String normalize(String s) {
        if (s == null) {
            return "";
        }
        String[] lines = s.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        List<String> kept = new ArrayList<>(lines.length);
        for (String line : lines) {
            kept.add(line.stripTrailing());
        }
        while (!kept.isEmpty() && kept.get(kept.size() - 1).isEmpty()) {
            kept.remove(kept.size() - 1);
        }
        while (!kept.isEmpty() && kept.get(0).isEmpty()) {
            kept.remove(0);
        }
        return String.join("\n", kept);
    }
}
