package com.freshmeat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DistanceCheckVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 实际测算距离 (km，保留两位小数) */
    private Double distance;

    /** 店铺配置的最大配送距离 (km) */
    private Double maxDeliveryDistance;

    /** 是否在配送范围内 */
    private Boolean canDeliver;

    /** 预计配送费 (元) */
    private BigDecimal deliveryFee;

    /** 起送金额 (元) */
    private BigDecimal minOrderAmount;

    /** 提示文案说明 */
    private String message;
}
