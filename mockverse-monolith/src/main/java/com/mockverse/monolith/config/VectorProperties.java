package com.mockverse.monolith.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 向量检索配置（D10）。 */
@Data
@Component
@ConfigurationProperties(prefix = "mockverse.vector")
public class VectorProperties {

    private String host = "localhost";
    private int port = 5433;
    private String database = "mockverse";
    private String username = "mockverse";
    private String password = "";

    /** 必须与 embedding 模型输出维度、建表 DDL 的 vector(N) 三者一致 */
    private int dimensions = 512;

    /** 默认召回条数 */
    private int topK = 3;
}
