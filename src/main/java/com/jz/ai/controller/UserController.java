package com.jz.ai.controller;

import com.jz.ai.common.CaptchaCache;
import com.jz.ai.common.Result;
import com.jz.ai.domain.dto.UserLoginDTO;
import com.jz.ai.domain.dto.UserRegisterDTO;
import com.jz.ai.domain.entity.User;
import com.jz.ai.domain.vo.UserVO;
import com.jz.ai.service.UserService;
import com.jz.ai.service.impl.CaptchaService;
import com.jz.ai.utils.CaptchaUtil;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.Cookie;
import org.springframework.web.bind.annotation.*;

import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.IOException;

@RestController
@RequestMapping("api/user")
public class UserController {
    @Resource
    private UserService userService;

    @Resource
    private CaptchaService captchaService;
    /**
     * 获取图片验证码（返回验证码图片）
     */
    @GetMapping("/captcha")
    public void getCaptcha(@RequestParam(defaultValue = "common")String type, HttpServletRequest request, HttpServletResponse response) throws IOException {
        // 1. 生成验证码和图片
        String code = CaptchaUtil.generateCode(4); // 如：4位字母数字组合
        BufferedImage image = CaptchaUtil.generateImage(code);

        // 2. 获取当前 SessionId
        String sessionId = request.getSession(true).getId();
        String key = sessionId+":"+type;
        // 3. 存入内存 Map
//        CaptchaCache.CAPTCHA_MAP.put(key, code.toLowerCase());  // 可统一转小写
        captchaService.saveCaptcha(key,code.toLowerCase());
        // 4. 禁止缓存并输出图像
        response.setHeader("Cache-Control", "no-store, no-cache");
        response.setHeader("Pragma", "no-cache");
        response.setDateHeader("Expires", 0);
        response.setContentType("image/png");
        ImageIO.write(image, "png", response.getOutputStream());
    }

    @GetMapping("/check")
    public Result<UserVO> checkSession(HttpServletRequest request) {
        User user = (User) request.getSession().getAttribute("user");
        if (user == null) {
            return Result.error("未登录");
        }
        UserVO userVO = new UserVO(user.getId(), user.getUsername(), user.getIsAdmin());
        return Result.success(userVO);
    }

    @PostMapping("/register")
    public Result<String> register(@RequestBody UserRegisterDTO dto, HttpServletRequest request) {
        String sessionId = request.getSession().getId();

        return userService.register(dto, sessionId,request);
    }

    /*
    * 返回user信息给前端存储起来，方便前端后续使用
    * */
    @PostMapping("/login")
    public Result<UserVO> login(@RequestBody UserLoginDTO dto, HttpServletRequest request) {
        String sessionId = request.getSession().getId();
        Result<User> userResult=userService.login(dto, sessionId, request);
        if(userResult.getCode()!=200){
            return Result.error(userResult.getMessage());
        }
        Long userId=userResult.getData().getId();
        request.getSession(true).setAttribute("UID",userId);
        return  Result.success(
                new UserVO(userId, userResult.getData().getUsername(),userResult.getData().getIsAdmin()
        ));
    }

    /*
    * 判断user是否存在根据用户名称
    *
    * */
    @GetMapping("/check-username")
    public Result<Boolean> checkUsername(@RequestParam String username) {
        return userService.checkUsername(username);
    }


    /**
     * 退出登录（幂等）
     */
    @PostMapping("/logout")
    public Result<String> logout(HttpServletRequest request, HttpServletResponse response) {
        // 1) 使当前会话失效（若没有会话则跳过）
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }

        // 2) 清除浏览器端 JSESSIONID（可选但更稳妥）
        Cookie cookie = new Cookie("JSESSIONID", null);
        cookie.setPath(request.getContextPath().isEmpty() ? "/" : request.getContextPath());
        cookie.setMaxAge(0);          // 立刻过期
        cookie.setHttpOnly(true);
        // 若生产是 HTTPS，可按你的配置决定是否加：
        // cookie.setSecure(true);
        response.addCookie(cookie);

        return Result.success("已退出登录");
    }
}
