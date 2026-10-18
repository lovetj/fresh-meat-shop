package com.freshmeat.dto;

import com.freshmeat.entity.ShopDeliveryRule;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DistanceCheckVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 实际测算距离 (km，保留两位小数) */
    private Double distance;

    /** 店铺配置的最大配送距离 (km，若为null或0表示不限距离) */
    private Double maxDeliveryDistance;

    /** 是否不限制配送距离 (true-不限距离/全城送) */
    private Boolean isUnlimitedDistance;

    /** 是否在配送范围内 */
    private Boolean canDeliver;

    /** 预计配送费 (元) */
    private BigDecimal deliveryFee;

    /** 起送金额 (元) */
    private BigDecimal minOrderAmount;

    /** 命中的阶梯规则详情 */
    private ShopDeliveryRule matchedRule;

    /** 店铺配置的所有配送阶梯规则列表 */
    private List<ShopDeliveryRule> deliveryRules;

    /** 提示文案说明 */
    private String message;
}
