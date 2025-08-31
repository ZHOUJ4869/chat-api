package com.jz.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.jz.ai.common.CaptchaCache;
import com.jz.ai.common.Result;
import com.jz.ai.domain.dto.UserLoginDTO;
import com.jz.ai.domain.dto.UserRegisterDTO;
import com.jz.ai.domain.entity.User;
import com.jz.ai.mapper.UserMapper;
import com.jz.ai.service.UserService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

@Service
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Resource
    private CaptchaService captchaService;
    public UserServiceImpl(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    public Result<Boolean> checkUsername(String username) {
        boolean exists = userMapper.selectCount(new QueryWrapper<User>().eq("username", username)) > 0;
        return Result.success(exists);
    }

    @Override
    public Result<User> login(UserLoginDTO dto, String sessionId, HttpServletRequest request) {
        String username = dto.getUsername();
        String password = dto.getPassword();
        String captcha = dto.getCaptcha();
        // 校验验证码
        String key=sessionId+":login";
        String realCaptcha = captchaService.getCaptcha(key);
        captchaService.deleteCaptcha(key);
        if (realCaptcha == null || !realCaptcha.equalsIgnoreCase(captcha)) {
            return Result.error("验证码错误或已过期");
        }
        // 校验格式
        if (!Pattern.matches("^[a-zA-Z0-9_]{6,16}$", username)) {
            return Result.error("用户名格式不正确");
        }

        if (!Pattern.matches("^(?=.*[A-Za-z])(?=.*\\d)[A-Za-z\\d]{6,}$", password)) {
            return Result.error("密码格式不正确");
        }

        // 查询用户
        User user = userMapper.selectOne(new QueryWrapper<User>().eq("username", username));
        if (user == null) {
            return Result.error("用户名不存在");
        }

        // 比对密码
        if (!encoder.matches(password, user.getPassword())) {
            return Result.error("密码错误");
        }

        // 登录成功，写入 session
        request.getSession().setAttribute("user", user);

        return Result.success(user);
    }

    @Override
    public Result<String> register(UserRegisterDTO dto, String sessionId, HttpServletRequest request) {
        String username = dto.getUsername();
        String password = dto.getPassword();
        String captcha = dto.getCaptcha();
        String key=sessionId+":register";
        // 校验验证码
        String realCaptcha = captchaService.getCaptcha(key);
        captchaService.deleteCaptcha(key);
        if (realCaptcha == null || !realCaptcha.equalsIgnoreCase(captcha)) {
            return Result.error("验证码错误或已过期");
        }

        // 校验用户名格式
        if (!Pattern.matches("^[a-zA-Z0-9_]{6,16}$", username)) {
            return Result.error("用户名需为6-16位，仅含字母、数字和下划线");
        }

        // 校验密码格式
        if (!Pattern.matches("^(?=.*[A-Za-z])(?=.*\\d)[A-Za-z\\d]{6,}$", password)) {
            return Result.error("密码至少6位，需包含字母和数字");
        }

        // 查重
        User exist = userMapper.selectOne(new QueryWrapper<User>().eq("username", username));
        if (exist != null) {
            return Result.error("用户名已存在");
        }
        // 加密密码并保存
        User user = new User();
        user.setUsername(username);
        user.setPassword(encoder.encode(password));
        user.setIsAdmin(false);
        userMapper.insert(user);
        // 注册成功，写入 session
        var session = request.getSession(true);
        session.setAttribute("user", user);
        session.setAttribute("UID",user.getId());
        return Result.success("注册成功");
    }
}