package com.freshmeat.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 快递配送运费试算参数
 */
@Data
public class ExpressCheckDTO {

    /** 门店ID，为空时使用默认营业门店 */
    private String shopId;

    /** 收货地址ID（必须是当前登录用户的地址） */
    @NotBlank(message = "请选择收货地址")
    private String addressId;
}
