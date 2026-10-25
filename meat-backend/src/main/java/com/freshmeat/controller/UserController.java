package com.freshmeat.controller;

import com.freshmeat.annotation.RequireLogin;
import com.freshmeat.common.Result;
import com.freshmeat.dto.LoginResultVO;
import com.freshmeat.dto.WxLoginDTO;
import com.freshmeat.service.UserService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Api(tags = "用户与认证模块")
@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /**
     * 微信小程序登录 (wx.login code 换取 token 与角色)
     */
    @ApiOperation(value = "微信小程序登录 (顾客端)", notes = "使用微信 wx.login 返回的 jsCode 换取 OpenID 及顾客端 JWT Token")
    @PostMapping("/wx/login")
    public Result<LoginResultVO> wxLogin(@RequestBody @Validated WxLoginDTO dto) {
        LoginResultVO result = userService.login(dto);
        return Result.success("登录成功", result);
    }

    /**
     * 超级管理员账号密码登录
     */
    @ApiOperation(value = "超级管理员账号密码登录 (掌柜端)", notes = "超级管理员专用登录接口，采用账号与密码进行鉴权，拒绝微信登录直接提权")
    @PostMapping("/admin/login")
    public Result<LoginResultVO> adminLogin(@RequestBody @Validated com.freshmeat.dto.AdminLoginDTO dto) {
        LoginResultVO result = userService.adminLogin(dto);
        return Result.success("管理员登录成功", result);
    }

    /**
     * 校验当前登录态是否仍然有效（单设备登录巡检）
     * Token 签名、会话标识（session_key）比对、账号状态校验均在 JwtInterceptor 完成：
     * 能进入本方法即代表当前登录态有效；被其他设备顶下线时拦截器直接返回 4011。
     */
    @ApiOperation(value = "校验当前登录态", notes = "供小程序前台定时巡检：账号被其他设备顶下线时返回 4011，需重新登录")
    @RequireLogin
    @GetMapping("/session")
    public Result<Boolean> checkSession() {
        return Result.success(true);
    }

    /**
     * 获取当前用户信息
     */
    @ApiOperation(value = "获取当前用户信息", notes = "根据 Header 中的 Token 获取当前登录用户信息")
    @RequireLogin
    @GetMapping("/info")
    public Result<LoginResultVO> getInfo() {
        LoginResultVO result = userService.getCurrentUserInfo();
        return Result.success(result);
    }

    /**
     * 更新当前登录用户资料（头像/昵称，顾客与掌柜通用）
     */
    @ApiOperation(value = "更新当前用户资料", notes = "仅更新 Token 对应的当前登录账号的头像/昵称，顾客端与掌柜端通用")
    @RequireLogin
    @PutMapping("/profile")
    public Result<LoginResultVO> updateProfile(@RequestBody com.freshmeat.dto.ProfileUpdateDTO dto) {
        LoginResultVO result = userService.updateProfile(dto);
        return Result.success("资料已更新", result);
    }

    /**
     * 角色视图切换 (管理员身份可在顾客端与商家端切换)
     */
    @ApiOperation(value = "角色视图切换", notes = "供测试或管理员在顾客端与掌柜管理端自由切换身份")
    @RequireLogin
    @PostMapping("/switch-role")
    public Result<LoginResultVO> switchRole(@RequestBody Map<String, String> body) {
        String targetRole = body.getOrDefault("targetRole", "CUSTOMER");
        LoginResultVO result = userService.switchRole(targetRole);
        return Result.success("角色切换成功", result);
    }
}
