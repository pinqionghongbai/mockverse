package com.mockverse.interview.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mockverse.interview.entity.User;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户 Mapper。
 *
 * <p>继承 BaseMapper 即获得单表 CRUD, 无需写 XML —— 这是 MyBatis-Plus 相对原生 MyBatis 的核心卖点,
 * 但复杂多表查询仍要手写 SQL (面试常问: 什么时候不该用 MP 的 Wrapper)。
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
}
