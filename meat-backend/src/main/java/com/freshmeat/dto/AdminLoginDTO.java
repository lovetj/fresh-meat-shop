package com.freshmeat.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
@ApiModel(description = "管理员账号密码登录参数")
public class AdminLoginDTO {

    @ApiModelProperty(value = "管理员登录账号", required = true, example = "admin")
    @NotBlank(message = "登录账号不能为空")
    private String username;

    @ApiModelProperty(value = "管理员登录密码", required = true, example = "admin123")
    @NotBlank(message = "登录密码不能为空")
    private String password;
}
