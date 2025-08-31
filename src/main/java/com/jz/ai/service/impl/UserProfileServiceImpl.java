package com.jz.ai.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jz.ai.domain.entity.UserProfile;
import com.jz.ai.mapper.UserProfileMapper;
import com.jz.ai.service.UserProfileService;
import org.springframework.stereotype.Service;

@Service
public class UserProfileServiceImpl
        extends ServiceImpl<UserProfileMapper, UserProfile>
        implements UserProfileService {}

