package com.mockverse.monolith.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 简历诊断结果实体（D11）。
 *
 * <p>{@code dimensions} 与 {@code issues} 在库里是 MySQL JSON 列，映射到 Java 侧就是
 * {@link String}——MyBatis 不知道 JSON 类型，我们存的是已经序列化好的 JSON 文本。
 * 这样做的代价是"读出来还得再解析一次"，收益是不用为两种只整体展示的数据拆子表。
 */
@Data
@TableName("resume_diagnosis")
public class ResumeDiagnosis {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    /** 原始简历文本，保留是为了将来换模型重跑时能对同一份输入做对比 */
    private String resumeText;

    private Integer overallScore;

    /** JSON 数组：[{name,score,strengths,weaknesses}] */
    private String dimensions;

    /** JSON 数组：[{severity,quote,problem,suggestion}] */
    private String issues;

    private String summary;

    /** 生成该结果的模型名，便于换模型后按 model 分组对比 */
    private String model;

    private Integer elapsedMs;

    private LocalDateTime createdAt;
}
