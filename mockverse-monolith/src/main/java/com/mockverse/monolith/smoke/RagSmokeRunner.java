package com.mockverse.monolith.smoke;

import com.mockverse.monolith.rag.KnowledgeChunk;
import com.mockverse.monolith.rag.KnowledgeLoader;
import com.mockverse.monolith.rag.KnowledgeService;
import com.mockverse.monolith.rag.PgVectorStore;
import com.mockverse.monolith.rag.RagAnswerService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * D10 验收：语料切分 → 向量入库 → 语义检索 → 带引用的 RAG 回答。
 *
 * <p>用法: {@code mvn spring-boot:run -Dspring-boot.run.profiles=rag}
 *
 * <p>三个检索查询刻意覆盖三种情况：
 * <ol>
 *   <li><b>术语原样命中</b>（"HashMap 扩容"）——最基本的要求，连这个都召回不到说明管线断了</li>
 *   <li><b>换一种说法</b>（"索引什么时候会失效"）——语料里的原文是"最左前缀"，考验语义而不是字面</li>
 *   <li><b>跨文件干扰</b>（"TCP 为什么要三次握手"）——50 篇里只有 network.md 有，验证不会串到别的分类</li>
 * </ol>
 *
 * <p>最后一步才调模型：**先证明检索对，再证明生成对**。反过来做的话，模型答得好会掩盖检索错误。
 */
@Component
@Profile("rag")
@Order(10)
@RequiredArgsConstructor
public class RagSmokeRunner implements ApplicationRunner {

    private static final String RAG_QUESTION = "HashMap 扩容的时候，元素的新位置是怎么算的？";

    private final KnowledgeLoader loader;
    private final KnowledgeService knowledgeService;
    private final RagAnswerService ragAnswerService;

    @Override
    public void run(ApplicationArguments args) {
        System.out.println("\n========== D10 RAG 冒烟开始 ==========");

        // [1] 切分统计：按分类数块，对不上就说明语料格式变了
        List<KnowledgeChunk> chunks = loader.loadAll();
        Map<String, Integer> byCategory = new TreeMap<>();
        for (KnowledgeChunk c : chunks) {
            byCategory.merge(c.category(), 1, Integer::sum);
        }
        System.out.println("[1] 语料切分: 共 " + chunks.size() + " 块 " + byCategory);
        System.out.println("    样例: [" + chunks.get(0).category() + "] " + chunks.get(0).title());

        // [2] 入库（已有则跳过）
        long begin = System.currentTimeMillis();
        int indexed = knowledgeService.ensureIndexed();
        System.out.println("[2] 入库: " + indexed + " 块, 耗时 " + (System.currentTimeMillis() - begin) + " ms");

        // [3] 检索
        String[][] queries = {
                {"术语原样", "HashMap 的扩容机制是什么"},
                {"换种说法", "索引什么时候会失效"},
                {"跨文件", "TCP 为什么要三次握手而不是两次"},
        };
        for (String[] q : queries) {
            System.out.println("\n[3] 查询（" + q[0] + "）: " + q[1]);
            List<PgVectorStore.Hit> hits = knowledgeService.search(q[1], 3);
            for (int i = 0; i < hits.size(); i++) {
                PgVectorStore.Hit h = hits.get(i);
                System.out.printf("    #%d score=%.4f [%s] %s <%s>%n",
                        i + 1, h.score(), h.category(), h.title(), h.source());
                System.out.println("        " + preview(h.content()));
            }
        }

        // [4] RAG 生成：检验"引用原文回答"，而不是模型自己背答案
        System.out.println("\n[4] RAG 问答: " + RAG_QUESTION);
        long genBegin = System.currentTimeMillis();
        RagAnswerService.Answer answer = ragAnswerService.answer(RAG_QUESTION);
        System.out.println("    耗时 " + (System.currentTimeMillis() - genBegin) + " ms");
        System.out.println("    回答:\n" + indent(answer.answer()));
        System.out.println("    引用来源:");
        for (int i = 0; i < answer.sources().size(); i++) {
            PgVectorStore.Hit h = answer.sources().get(i);
            System.out.printf("      [%d] %s / %s (%.4f)%n", i + 1, h.source(), h.title(), h.score());
        }

        System.out.println("\n========== D10 RAG 冒烟结束 ==========\n");
    }

    private static String preview(String s) {
        String oneLine = s.replaceAll("\\s+", " ");
        return oneLine.length() <= 70 ? oneLine : oneLine.substring(0, 70) + "…";
    }

    private static String indent(String s) {
        return s.lines().map(l -> "      " + l).reduce("", (a, b) -> a + b + "\n");
    }
}
