package com.freshmeat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginResultVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** JWT Token 访问凭证 */
    private String token;

    /** 用户 ID */
    private String userId;

    /** 微信 openid */
    private String openid;

    /** 昵称 */
    private String nickname;

    /** 头像 */
    private String avatarUrl;

    /** 手机号 */
    private String phone;

    /** 角色标识: CUSTOMER / ADMIN */
    private String role;

    /** 是否是管理员 */
    private Boolean isAdmin;
}
