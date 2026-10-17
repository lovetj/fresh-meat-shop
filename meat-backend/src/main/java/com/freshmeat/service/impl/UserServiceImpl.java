package com.freshmeat.service.impl;

import cn.hutool.http.HttpUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.freshmeat.config.WechatProperties;
import com.freshmeat.dto.LoginResultVO;
import com.freshmeat.dto.WxLoginDTO;
import com.freshmeat.entity.User;
import com.freshmeat.exception.BusinessException;
import com.freshmeat.mapper.UserMapper;
import com.freshmeat.service.UserService;
import com.freshmeat.util.JwtUtil;
import com.freshmeat.util.SecurityContextUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    private final WechatProperties wechatProperties;
    private final JwtUtil jwtUtil;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LoginResultVO login(WxLoginDTO dto) {
        String code = dto.getCode().trim();
        String openid;

        // 1. 获取微信 openid（微信登录一律只能作为普通顾客 CUSTOMER，禁止任何提权为 ADMIN 的通道）
        if (Boolean.TRUE.equals(wechatProperties.getMiniapp().getMock())) {
            if ("customer_code".equalsIgnoreCase(code) || "customer".equalsIgnoreCase(code)) {
                openid = "customer_openid_demo";
            } else {
                openid = "wx_mock_" + Math.abs(code.hashCode());
            }
        } else {
            // 请求微信官方 jscode2session 接口
            openid = fetchWxOpenid(code);
        }

        // 2. 查询用户是否存在
        User user = this.getOne(new LambdaQueryWrapper<User>().eq(User::getOpenid, openid));
        if (user == null) {
            // 微信端新用户注册，角色恒定为普通顾客 CUSTOMER
            user = User.builder()
                    .openid(openid)
                    .nickname(StringUtils.hasText(dto.getNickname()) ? dto.getNickname() : "微信顾客")
                    .avatarUrl(StringUtils.hasText(dto.getAvatarUrl()) ? com.freshmeat.util.FileUrlUtil.toRelative(dto.getAvatarUrl()) : "")
                    .phone(StringUtils.hasText(dto.getPhone()) ? dto.getPhone() : "")
                    .role("CUSTOMER")
                    .status(1)
                    .lastLoginTime(LocalDateTime.now())
                    .build();
            this.save(user);
        } else {
            // 更新用户信息
            if (StringUtils.hasText(dto.getNickname())) {
                user.setNickname(dto.getNickname());
            }
            if (StringUtils.hasText(dto.getAvatarUrl())) {
                user.setAvatarUrl(com.freshmeat.util.FileUrlUtil.toRelative(dto.getAvatarUrl()));
            }
            if (StringUtils.hasText(dto.getPhone())) {
                user.setPhone(dto.getPhone());
            }
            user.setLastLoginTime(LocalDateTime.now());
            this.updateById(user);
        }

        if (user.getStatus() != 1) {
            throw new BusinessException(403, "账号已被禁用，请联系客服");
        }

        // 3. 微信登录强制签发顾客态 Token（有效期30天，角色为 CUSTOMER）
        String token = jwtUtil.generateToken(user.getId(), user.getOpenid(), "CUSTOMER");

        return LoginResultVO.builder()
                .token(token)
                .userId(user.getId())
                .openid(user.getOpenid())
                .nickname(user.getNickname())
                .avatarUrl(user.getAvatarUrl())
                .phone(user.getPhone())
                .role("CUSTOMER")
                .isAdmin(false)
                .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LoginResultVO adminLogin(com.freshmeat.dto.AdminLoginDTO dto) {
        String username = dto.getUsername().trim();
        String password = dto.getPassword().trim();

        // 1. 查询管理员账号
        User adminUser = this.getOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (adminUser == null) {
            throw new BusinessException("管理员账号或密码错误");
        }

        // 2. 身份防线：必须为 ADMIN 角色
        if (!"ADMIN".equalsIgnoreCase(adminUser.getRole())) {
            throw new BusinessException(403, "该账号未授权管理员权限");
        }

        // 3. 密码校验：BCrypt 哈希比对（兼容初次明文初始化密码比对与历史旧伪哈希容错，并自动升级为安全哈希）
        String dbPassword = adminUser.getPassword();
        boolean matches = false;
        final String legacyDummyHash = "$2a$10$h9WzI5D5j8UaV1k4X7jGRe3J9y1F.K1i.Q/uK6y.6e7.E1c6a7v4i";
        final String standardAdmin123Hash = "$2a$10$zyQBYYn1TXYtm8CGwQyHsux9OXWVYTczvXPYFpacp4cCV5CFJfPBS";

        if (StringUtils.hasText(dbPassword)) {
            // 兼容旧版特定伪哈希或明文 admin123 容错
            if (legacyDummyHash.equals(dbPassword) && "admin123".equals(password)) {
                matches = true;
                adminUser.setPassword(standardAdmin123Hash);
                this.updateById(adminUser);
            } else if (dbPassword.startsWith("$2a$") || dbPassword.startsWith("$2b$") || dbPassword.startsWith("$2y$")) {
                try {
                    matches = cn.hutool.crypto.digest.BCrypt.checkpw(password, dbPassword);
                } catch (Exception e) {
                    log.warn("BCrypt 比对异常: {}", e.getMessage());
                    matches = false;
                }
            } else {
                // 兼容初始明文配置比对并自动升级加密
                matches = password.equals(dbPassword);
                if (matches) {
                    adminUser.setPassword(cn.hutool.crypto.digest.BCrypt.hashpw(password));
                    this.updateById(adminUser);
                }
            }
        }

        if (!matches) {
            throw new BusinessException("管理员账号或密码错误");
        }

        // 4. 检查账号状态
        if (adminUser.getStatus() != 1) {
            throw new BusinessException(403, "该管理员账号已被禁用");
        }

        // 5. 更新最近登录时间
        adminUser.setLastLoginTime(LocalDateTime.now());
        this.updateById(adminUser);

        // 6. 签发管理员专属高权 JWT Token (专属 7 天登录态)
        String token = jwtUtil.generateToken(adminUser.getId(), adminUser.getOpenid(), "ADMIN");

        return LoginResultVO.builder()
                .token(token)
                .userId(adminUser.getId())
                .openid(adminUser.getOpenid())
                .nickname(StringUtils.hasText(adminUser.getNickname()) ? adminUser.getNickname() : adminUser.getUsername())
                .avatarUrl(adminUser.getAvatarUrl())
                .phone(adminUser.getPhone())
                .role("ADMIN")
                .isAdmin(true)
                .build();
    }

    @Override
    public LoginResultVO switchRole(String targetRole) {
        String userId = SecurityContextUtil.getUserId();
        if (userId == null) {
            throw new BusinessException(401, "未登录");
        }
        User user = this.getById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }

        // 只有账号本身具有管理员权限，才允许切换到商家端 ADMIN
        if (!"ADMIN".equalsIgnoreCase(user.getRole())) {
            throw new BusinessException(403, "非管理员无法切换至商家端");
        }

        String effectiveRole = "CUSTOMER".equalsIgnoreCase(targetRole) ? "CUSTOMER" : "ADMIN";
        String token = jwtUtil.generateToken(user.getId(), user.getOpenid(), effectiveRole);

        return LoginResultVO.builder()
                .token(token)
                .userId(user.getId())
                .openid(user.getOpenid())
                .nickname(user.getNickname())
                .avatarUrl(user.getAvatarUrl())
                .phone(user.getPhone())
                .role(effectiveRole)
                .isAdmin(true)
                .build();
    }

    @Override
    public LoginResultVO getCurrentUserInfo() {
        String userId = SecurityContextUtil.getUserId();
        if (userId == null) {
            throw new BusinessException(401, "未登录");
        }
        User user = this.getById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        return LoginResultVO.builder()
                .userId(user.getId())
                .openid(user.getOpenid())
                .nickname(user.getNickname())
                .avatarUrl(user.getAvatarUrl())
                .phone(user.getPhone())
                .role(user.getRole())
                .isAdmin("ADMIN".equalsIgnoreCase(user.getRole()))
                .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LoginResultVO updateProfile(com.freshmeat.dto.ProfileUpdateDTO dto) {
        String userId = SecurityContextUtil.getUserId();
        if (userId == null) {
            throw new BusinessException(401, "未登录");
        }
        if (dto == null) {
            throw new BusinessException("参数不能为空");
        }

        User user = this.getById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }

        if (StringUtils.hasText(dto.getNickname())) {
            user.setNickname(dto.getNickname().trim());
        }
        if (StringUtils.hasText(dto.getAvatarUrl())) {
            user.setAvatarUrl(com.freshmeat.util.FileUrlUtil.toRelative(dto.getAvatarUrl()));
        }
        if (StringUtils.hasText(dto.getPhone())) {
            user.setPhone(dto.getPhone().trim());
        }
        this.updateById(user);

        return LoginResultVO.builder()
                .userId(user.getId())
                .openid(user.getOpenid())
                .nickname(user.getNickname())
                .avatarUrl(user.getAvatarUrl())
                .phone(user.getPhone())
                .role(SecurityContextUtil.getRole())
                .isAdmin(SecurityContextUtil.isAdmin())
                .build();
    }

    /**
     * 调用微信官方 API 获取 openid
     */
    private String fetchWxOpenid(String code) {
        String url = String.format(
                "https://api.weixin.qq.com/sns/jscode2session?appid=%s&secret=%s&js_code=%s&grant_type=authorization_code",
                wechatProperties.getMiniapp().getAppid(),
                wechatProperties.getMiniapp().getSecret(),
                code
        );
        try {
            String response = HttpUtil.get(url, 5000);
            JSONObject json = JSON.parseObject(response);
            if (json.containsKey("openid")) {
                return json.getString("openid");
            } else {
                log.warn("微信登录 code2Session 失败: {}", response);
                throw new BusinessException("微信登录授权失败: " + json.getString("errmsg"));
            }
        } catch (Exception e) {
            log.error("微信接口请求异常，降级处理: {}", e.getMessage());
            // 若配置无法连通外网或测试环境，降级返回基于 code 生成的 openid
            return "wx_mock_" + Math.abs(code.hashCode());
        }
    }
}
