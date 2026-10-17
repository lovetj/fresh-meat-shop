package com.freshmeat.dto;

import lombok.Data;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;

@Data
public class DistanceCheckDTO {

    /** 目标店铺ID (可选，默认取首个营业店铺) */
    private String shopId;

    /** 顾客收货地址经度 */
    @NotNull(message = "收货地址经度不能为空")
    private BigDecimal longitude;

    /** 顾客收货地址纬度 */
    @NotNull(message = "收货地址纬度不能为空")
    private BigDecimal latitude;
}
