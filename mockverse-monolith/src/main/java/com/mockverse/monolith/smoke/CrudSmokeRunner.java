package com.mockverse.monolith.smoke;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mockverse.monolith.entity.ChatMessage;
import com.mockverse.monolith.entity.InterviewSession;
import com.mockverse.monolith.entity.User;
import com.mockverse.monolith.mapper.ChatMessageMapper;
import com.mockverse.monolith.mapper.InterviewSessionMapper;
import com.mockverse.monolith.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * CRUD 冒烟: 建用户 → 开会话 → 插消息 → 查历史。
 *
 * <p>只在 {@code --spring.profiles.active=smoke} 时执行, 不污染正常启动流程。
 * 用法: {@code mvnw spring-boot:run -Dspring-boot.run.profiles=smoke}
 */
@Component
@Profile("smoke")
@RequiredArgsConstructor
public class CrudSmokeRunner implements ApplicationRunner {

    private final UserMapper userMapper;
    private final InterviewSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;

    @Override
    public void run(ApplicationArguments args) {
        System.out.println("\n========== D8 CRUD 冒烟开始 ==========");

        // 1. 建用户 (主键由 MyBatis-Plus 雪花算法回填)
        User user = new User();
        user.setUsername("smoke_" + System.currentTimeMillis());
        user.setPasswordHash("$2a$10$smoke_placeholder_not_a_real_hash");
        user.setNickname("冒烟测试员");
        userMapper.insert(user);
        System.out.println("[1] 建用户      id=" + user.getId() + " username=" + user.getUsername());

        // 2. 开会话
        InterviewSession session = new InterviewSession();
        session.setUserId(user.getId());
        session.setTitle("D8 冒烟面试");
        session.setStatus("IN_PROGRESS");
        session.setStartedAt(LocalDateTime.now());
        sessionMapper.insert(session);
        System.out.println("[2] 开会话      id=" + session.getId() + " status=" + session.getStatus());

        // 3. 插两条消息 (seq_no 受唯一索引 uk_session_seq 保护)
        insertMessage(session.getId(), 1, "USER", null, "我来参加模拟面试了");
        insertMessage(session.getId(), 2, "AGENT", "出题", "请解释 MySQL 联合索引的最左前缀原则");
        System.out.println("[3] 插消息      2 条 (seq_no=1,2)");

        // 4. 查历史 —— 走 uk_session_seq, 天然有序, 不需要 ORDER BY filesort
        List<ChatMessage> history = messageMapper.selectList(
                Wrappers.<ChatMessage>lambdaQuery()
                        .eq(ChatMessage::getSessionId, session.getId())
                        .orderByAsc(ChatMessage::getSeqNo));
        System.out.println("[4] 查历史      命中 " + history.size() + " 条:");
        history.forEach(m -> System.out.printf("      seq=%d role=%-5s agent=%-4s content=%s%n",
                m.getSeqNo(), m.getRole(), m.getAgentName() == null ? "-" : m.getAgentName(), m.getContent()));

        // 5. 更新 + 回读 (验证 updated_at 的 ON UPDATE 生效)
        session.setStatus("COMPLETED");
        session.setEndedAt(LocalDateTime.now());
        sessionMapper.updateById(session);
        InterviewSession reloaded = sessionMapper.selectById(session.getId());
        System.out.println("[5] 更新会话    status=" + reloaded.getStatus()
                + " updatedAt=" + reloaded.getUpdatedAt());

        System.out.println("========== D8 CRUD 冒烟结束 (5/5 通过) ==========\n");
    }

    private void insertMessage(Long sessionId, int seqNo, String role, String agentName, String content) {
        ChatMessage msg = new ChatMessage();
        msg.setSessionId(sessionId);
        msg.setSeqNo(seqNo);
        msg.setRole(role);
        msg.setAgentName(agentName);
        msg.setContent(content);
        msg.setTokenCount(content.length());
        messageMapper.insert(msg);
    }
}
