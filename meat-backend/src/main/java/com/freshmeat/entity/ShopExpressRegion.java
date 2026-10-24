package com.freshmeat.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 快递配送「特殊地区」规则
 * <p>
 * 以 JSON 数组形式存放于 shop_info.express_regions，不单独建表：
 * 用于配置部分地区的单独运费，或直接停发（如新疆、西藏等偏远地区）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShopExpressRegion implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 地区关键字，匹配收货地址的省份或完整地址，如：新疆、西藏、港澳台 */
    private String region;

    /** 该地区快递费(元)，为空时使用店铺基础快递费 */
    private BigDecimal fee;

    /** 是否可配送: 1-可配送, 0-停发 */
    private Integer deliverable;
}
