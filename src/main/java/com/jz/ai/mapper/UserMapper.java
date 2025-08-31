package com.jz.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jz.ai.domain.entity.User;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<User> {}

