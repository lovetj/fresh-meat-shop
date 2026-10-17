package com.freshmeat.util;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 线程安全的用户登录上下文管理
 */
public class SecurityContextUtil {

    private static final ThreadLocal<LoginUser> USER_HOLDER = new ThreadLocal<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LoginUser {
        private String userId;
        private String openid;
        private String role; // CUSTOMER / ADMIN
    }

    public static void setUser(LoginUser user) {
        USER_HOLDER.set(user);
    }

    public static LoginUser getUser() {
        return USER_HOLDER.get();
    }

    public static String getUserId() {
        LoginUser user = USER_HOLDER.get();
        return user != null ? user.getUserId() : null;
    }

    public static String getOpenid() {
        LoginUser user = USER_HOLDER.get();
        return user != null ? user.getOpenid() : null;
    }

    public static String getRole() {
        LoginUser user = USER_HOLDER.get();
        return user != null ? user.getRole() : "CUSTOMER";
    }

    public static boolean isAdmin() {
        return "ADMIN".equalsIgnoreCase(getRole());
    }

    public static void clear() {
        USER_HOLDER.remove();
    }
}
