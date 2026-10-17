package com.freshmeat.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.freshmeat.dto.LoginResultVO;
import com.freshmeat.dto.WxLoginDTO;
import com.freshmeat.entity.User;

public interface UserService extends IService<User> {

    /**
     * 微信小程序登录 (wx.login code 换取 openid 并签发顾客态 JWT)
     */
    LoginResultVO login(WxLoginDTO dto);

    /**
     * 超级管理员账号密码登录 (签发管理员专属高权 JWT)
     */
    LoginResultVO adminLogin(com.freshmeat.dto.AdminLoginDTO dto);

    /**
     * 切换当前角色 (仅限系统管理员可在 CUSTOMER 与 ADMIN 间切换视图)
     */
    LoginResultVO switchRole(String targetRole);

    /**
     * 获取当前登录用户信息
     */
    LoginResultVO getCurrentUserInfo();

    /**
     * 更新当前登录用户资料（头像/昵称/手机号，顾客与掌柜通用）
     */
    LoginResultVO updateProfile(com.freshmeat.dto.ProfileUpdateDTO dto);
}
