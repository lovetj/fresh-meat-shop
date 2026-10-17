package com.freshmeat.dto;

import lombok.Data;
import javax.validation.constraints.NotBlank;

@Data
public class WxLoginDTO {

    /** wx.login 获取的临时登录凭证 code */
    @NotBlank(message = "code不能为空")
    private String code;

    /** 用户昵称 (可选) */
    private String nickname;

    /** 用户头像 (可选) */
    private String avatarUrl;

    /** 手机号 (可选) */
    private String phone;
}
