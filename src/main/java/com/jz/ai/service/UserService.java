package com.jz.ai.service;

import com.jz.ai.common.Result;
import com.jz.ai.domain.dto.UserLoginDTO;
import com.jz.ai.domain.dto.UserRegisterDTO;
import com.jz.ai.domain.entity.User;
import jakarta.servlet.http.HttpServletRequest;

public interface UserService {
    Result<Boolean> checkUsername(String username);
    Result<String> register(UserRegisterDTO dto, String sessionId, HttpServletRequest request);
    Result<User> login(UserLoginDTO dto, String sessionId, HttpServletRequest request);
}
