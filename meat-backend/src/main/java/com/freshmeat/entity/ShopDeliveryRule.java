package com.freshmeat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("shop_delivery_rule")
public class ShopDeliveryRule implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 所属店铺UUID */
    private String shopId;

    /** 起始距离 (km) */
    private BigDecimal minDistance;

    /** 截止距离 (km) */
    private BigDecimal maxDistance;

    /** 该距离区间的起送金额 (元) */
    private BigDecimal minOrderAmount;

    /** 该距离区间的配送费 (元) */
    private BigDecimal deliveryFee;

    /** 排序权重 */
    private Integer sortOrder;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
