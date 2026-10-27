package com.mockverse.judge.judge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 判题的「文件名 / 启动类」识别单测（D29 补）。
 *
 * <p><b>为什么专门测这个</b>：这里踩过两次**同族病灶**（D18 实测 + D18 六查）——
 * <ul>
 *   <li>取"第一个类型"：候选人把 {@code Helper} 写在前面时，会去跑 {@code Helper}，
 *       得到"找不到 main 方法"，**把完全正确的代码判成错**；</li>
 *   <li>取"main 之前最后一个类型"：{@code class Solution { static class Node {...} main }}
 *       会取到嵌套的 {@code Node}，同样失败。</li>
 * </ul>
 * 这两条都是"看起来合理"的实现，只有用真实代码形状当用例才会暴露。所以下面每个用例
 * 都对应一种候选人真会写的写法。
 *
 * <p>纯函数测试，不编译、不执行。
 */
class JavaJudgeClassDetectionTest {

    @Test
    @DisplayName("public 类优先作为文件名；没有 public 类时返回 null")
    void detectsPublicType() {
        assertEquals("Main", JavaJudge.detectPublicType(
                "public class Main { public static void main(String[] a) {} }"));
        assertEquals("Str", JavaJudge.detectPublicType(
                "class Helper {} public class Str { public static void main(String[] a) {} }"));
        assertNull(JavaJudge.detectPublicType(
                "class NoPublic { public static void main(String[] a) {} }"));
    }

    @Test
    @DisplayName("注释 / 字符串里的 class 字样不能被当成类型声明")
    void ignoresClassKeywordInCommentsAndStrings() {
        String code = """
                // public class Fake { }
                public class Real {
                    public static void main(String[] a) {
                        System.out.println("class Main inside string");
                    }
                }
                """;
        assertEquals("Real", JavaJudge.detectPublicType(code));
        assertEquals("Real", JavaJudge.detectMainClass(code));
    }

    @Test
    @DisplayName("辅助类写在前面时，启动类仍是包住 main 的那个（D18 第一个坑）")
    void mainClassNotTheFirstType() {
        String code = """
                class Helper {
                    static int add(int a, int b) { return a + b; }
                }
                class Solution {
                    public static void main(String[] args) {
                        System.out.println(Helper.add(1, 2));
                    }
                }
                """;
        assertEquals("Solution", JavaJudge.detectMainClass(code),
                "不能取第一个类型 Helper——那会得到\"找不到 main 方法\"");
        assertNull(JavaJudge.detectPublicType(code), "没有 public 类");
    }

    @Test
    @DisplayName("main 之前有嵌套静态类时，启动类是最外层那个（D18 六查第二个坑）")
    void nestedTypeBeforeMainDoesNotWin() {
        String code = """
                class Solution {
                    static class Node {
                        int val;
                        Node next;
                    }
                    public static void main(String[] args) {
                        Node n = new Node();
                        System.out.println(n.val);
                    }
                }
                """;
        assertEquals("Solution", JavaJudge.detectMainClass(code),
                "main 之前最后一个类型是嵌套的 Node，取它会跑不起来");
    }

    @Test
    @DisplayName("没有 main 方法时返回 null，由调用方回落")
    void noMainReturnsNull() {
        assertNull(JavaJudge.detectMainClass("class A { void f() {} }"));
        // 注释里的 main 不算
        assertNull(JavaJudge.detectMainClass("// main\nclass A { void f() {} }"));
    }

    @Test
    @DisplayName("main 在嵌套类里时，返回那个嵌套类的名字")
    void mainInsideNestedType() {
        String code = """
                class Outer {
                    static class Inner {
                        public static void main(String[] args) { }
                    }
                }
                """;
        assertEquals("Inner", JavaJudge.detectMainClass(code));
    }
}