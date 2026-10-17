package com.freshmeat.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

/**
 * 当前登录用户资料更新参数（顾客与掌柜通用，仅作用于 Token 对应的账号）
 */
@ApiModel("个人资料更新参数")
@Data
public class ProfileUpdateDTO {

    @ApiModelProperty("昵称（为空则不修改）")
    private String nickname;

    @ApiModelProperty("头像路径（为空则不修改，入库自动转相对路径）")
    private String avatarUrl;

    @ApiModelProperty("手机号（为空则不修改）")
    private String phone;
}
