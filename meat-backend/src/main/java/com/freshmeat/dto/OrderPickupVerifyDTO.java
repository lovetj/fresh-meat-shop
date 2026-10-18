package com.freshmeat.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
@ApiModel("掌柜自提码核销请求DTO")
public class OrderPickupVerifyDTO {

    @ApiModelProperty(value = "顾客出示的自提提货码", required = true)
    @NotBlank(message = "核销提货码不能为空")
    private String pickupCode;

    @ApiModelProperty(value = "指定核销商铺ID(可选，若不传则基于当前管理员管辖分店)")
    private String shopId;
}
