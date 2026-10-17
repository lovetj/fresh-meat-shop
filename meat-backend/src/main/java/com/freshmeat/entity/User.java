package com.freshmeat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("sys_user")
public class User implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 微信 openid */
    private String openid;

    /** 微信 unionid */
    private String unionid;

    /** 管理员登录账号 */
    private String username;

    /** 管理员登录密码(哈希加密) */
    private String password;

    /** 用户昵称 */
    private String nickname;

    /** 头像 URL */
    private String avatarUrl;

    /** 绑定手机号 */
    private String phone;

    /** 角色: CUSTOMER-顾客, ADMIN-商家管理员 */
    private String role;

    /** 状态: 1-启用, 0-禁用 */
    private Integer status;

    /** 最近登录时间 */
    private LocalDateTime lastLoginTime;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
