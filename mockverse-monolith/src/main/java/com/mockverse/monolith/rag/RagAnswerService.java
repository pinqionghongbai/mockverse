package com.mockverse.monolith.rag;

import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * RAG 问答：检索 → 拼上下文 → 生成带引用的回答。
 *
 * <p><b>提示词的三条硬约束</b>（都是踩过坑才知道要写的）：
 * <ol>
 *   <li><b>只依据资料回答</b>——不加这条，模型会用自己的参数化知识作答，检索就白做了</li>
 *   <li><b>必须标注引用编号</b>——不加这条，回答看起来对但无法核查，等于没做 RAG</li>
 *   <li><b>资料里没有就说没有</b>——不加这条，模型会硬编一个答案，这是 RAG 最危险的失败模式</li>
 * </ol>
 */
@Slf4j
@Service
public class RagAnswerService {

    private final KnowledgeService knowledgeService;

    /**
     * 容器里有多个 {@code ChatModel}（ragChatModel / diagnosisChatModel），
     * 所以必须显式 {@code @Qualifier}。Lombok 的 {@code @RequiredArgsConstructor}
     * 不会把字段上的注解复制到构造参数，因此这里手写构造器。
     */
    private final ChatModel chatModel;

    public RagAnswerService(KnowledgeService knowledgeService,
                            @Qualifier("ragChatModel") ChatModel chatModel) {
        this.knowledgeService = knowledgeService;
        this.chatModel = chatModel;
    }

    public record Answer(String question, String answer, List<PgVectorStore.Hit> sources) {}

    public Answer answer(String question) {
        List<PgVectorStore.Hit> hits = knowledgeService.search(question);

        StringBuilder context = new StringBuilder();
        for (int i = 0; i < hits.size(); i++) {
            PgVectorStore.Hit h = hits.get(i);
            context.append("[%d] 来源: %s / %s（相似度 %.3f）%n%s%n%n"
                    .formatted(i + 1, h.source(), h.title(), h.score(), h.content()));
        }

        String prompt = """
                你是 Java 后端面试辅导助手。请**只依据下面的参考资料**回答候选人的问题。

                参考资料：
                %s
                问题：%s

                回答要求：
                1. 每个结论后面用 [编号] 标注它来自哪条资料，例如"扩容会翻倍 [1]"。
                2. 资料里没有提到的内容，直接说"资料未覆盖"，不要用自己的知识补充。
                3. 用 200 字以内讲清楚，不要罗列无关细节。
                """.formatted(context, question);

        long begin = System.currentTimeMillis();
        String reply = chatModel.chat(prompt);
        log.info("RAG 生成完成, 召回 {} 条, 耗时 {} ms", hits.size(), System.currentTimeMillis() - begin);

        return new Answer(question, reply, hits);
    }
}
