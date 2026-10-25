package com.freshmeat.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.freshmeat.entity.User;
import com.freshmeat.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.UUID;

/**
 * 单设备登录会话管理
 *
 * 设计说明（无 Redis / 无状态 JWT 的前提下实现「同一账号仅一台设备在线」）：
 * 1. sys_user.session_key 保存该账号「当前有效登录会话标识」；
 * 2. 每次登录（微信顾客登录 / 管理员账号密码登录）都刷新该标识，并写入新签发的 Token；
 * 3. 每次鉴权时比对 Token 中的会话标识与数据库中的当前值：
 *    不一致即代表账号已在其他设备登录，旧 Token 立即失效（而非等到过期）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenSessionService {

    private final UserMapper userMapper;

    /**
     * 登录时刷新会话标识：顶掉该账号在其他设备上的登录态
     *
     * @param userId 用户ID
     * @return 新会话标识（写入本次签发的 Token）
     */
    public String renewSession(String userId) {
        String sessionKey = UUID.randomUUID().toString().replace("-", "");
        int rows = userMapper.update(null, new LambdaUpdateWrapper<User>()
                .eq(User::getId, userId)
                .set(User::getSessionKey, sessionKey));
        if (rows <= 0) {
            log.warn("刷新登录会话标识失败, userId={}", userId);
        }
        return sessionKey;
    }

    /**
     * 查询用户当前记录（用于鉴权时校验会话标识、账号状态）
     *
     * @param userId 用户ID
     * @return 用户实体，不存在返回 null
     */
    public User getSessionUser(String userId) {
        if (!StringUtils.hasText(userId)) {
            return null;
        }
        return userMapper.selectById(userId);
    }
}
