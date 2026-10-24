package com.mockverse.resume.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 插件配置。
 *
 * <p><b>为什么必须有这个类</b>：{@code selectPage()} 在**没注册分页拦截器**时不会报错，
 * 它只是老老实实把全表查出来，然后 {@code Page.getRecords()} 返回全部结果——看起来"查到了"，
 * 实际 LIMIT 从来没拼进 SQL。这是个沉默的失效：日志里只有一条 warn，很容易被当成噪音。
 * 所以分页能力必须显式注册，不能靠默认值。
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 指定 DbType 而不是让插件自己探测：探测要额外一次连接元数据查询
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
