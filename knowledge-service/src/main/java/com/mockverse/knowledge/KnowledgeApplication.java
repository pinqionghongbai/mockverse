package com.mockverse.knowledge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

/**
 * 知识库服务入口（D15 自单体机械拆分而来）。
 *
 * <p><b>显式排除 {@code DataSourceAutoConfiguration}</b>：本服务的数据源只有 pgvector，
 * 由 {@code PgVectorStore} 组件私有的 HikariCP 连接池直连，不走 Spring 的 DataSource
 * 自动配置。类路径上有 spring-boot-starter-jdbc（JdbcTemplate/HikariCP 是检索要用的），
 * 不排除的话自动配置会因找不到 {@code spring.datasource.url} 而启动失败。
 */
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
public class KnowledgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(KnowledgeApplication.class, args);
    }
}
