package com.mockverse.judge.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson 序列化配置（D25）。
 *
 * <p><b>要解决的问题</b>：本项目主键是 MyBatis-Plus 的雪花 ID（19 位，如
 * {@code 2114734947751882754}），而 JavaScript 的 {@code Number} 只能精确表示到
 * {@code 2^53-1 = 9007199254740991}（16 位）。超出的部分会被**静默截断**：
 *
 * <pre>
 *   真实 ID       : 2114734947751882754
 *   JS 读成       : 2114734947751882752     ← 差了 2
 * </pre>
 *
 * <p><b>症状有多迷惑</b>：前端拿 {@code start} 返回的 sessionId 去请求
 * {@code /answer/stream}，后端查不到会话 → 400。而浏览器里显示的数字看起来完全正常，
 * 只有对着数据库里那一行才能看出末位不对。D25 就是这么踩到的。
 *
 * <p><b>为什么改后端而不是改前端</b>：前端要处理就必须在 JSON.parse 之前用正则改写
 * 所有大整数（或引入 json-bigint 之类库），任何一次手写的 {@code JSON.parse} 都会漏。
 * 后端把 Long 序列化成字符串是一劳永逸的——代价是前端拿到的是字符串，拼 URL 时无需转换。
 *
 * <p><b>为什么只转 Long，不转 Integer</b>：{@code round} / {@code maxRounds} 是小整数，
 * 保持数字类型对前端更自然（能直接做算术、比较）。只有 ID 类字段需要字符串。
 *
 * <p><b>副作用（已知）</b>：请求体里如果传 Long（本项目的接口都是 path variable 或
 * query param，没有 JSON body 里的 Long），Jackson 仍能反序列化字符串形式的数字。
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer bigNumberAsString() {
        return builder -> {
            SimpleModule module = new SimpleModule();
            // Long / long 都注册：包装类型与基本类型都会出现在 DTO 里
            module.addSerializer(Long.class, ToStringSerializer.instance);
            module.addSerializer(Long.TYPE, ToStringSerializer.instance);
            builder.modulesToInstall(module);
        };
    }
}
