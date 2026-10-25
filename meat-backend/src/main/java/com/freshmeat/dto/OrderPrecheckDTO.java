package com.freshmeat.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 下单前统一校验请求
 *
 * <p>字段结构与 {@link OrderCreateDTO} 完全对齐，额外携带前端页面展示的快照信息
 * （单价 / 商品金额 / 配送费 / 实付金额），用于校验「价格、规格、运费是否一致」。</p>
 *
 * <p>所有快照字段均为可选：不传时仅做服务端可判定的校验（商品/分类/门店/配送范围/期望时间），
 * 保证老版本客户端仍然可用。</p>
 */
@Data
public class OrderPrecheckDTO {

    /** 目标商铺ID (可选，若不传则自动取商品所属店铺) */
    private String shopId;

    /** 配送方式: 1-自取, 2-商家配送, 3-外卖(保留入口), 4-快递配送 */
    private Integer deliveryType;

    /** 支付方式: 1-微信线上支付, 2-货到付款 (校验不强制) */
    private Integer payType;

    /** 商家配送/快递配送时的收货地址ID */
    private String addressId;

    /** 自取人姓名 (自提时可选) */
    private String pickupName;

    /** 自取人联系电话 (自提时可选) */
    private String pickupPhone;

    /** 期望送达/到店自取时间 (格式: yyyy-MM-dd HH:mm) */
    private String expectedTime;

    /** 购买商品ID (单商品下单兼容) */
    private String productId;

    /** 购买数量/份数 (单商品下单兼容) */
    private Integer quantity;

    /** 所选规格信息 (单商品下单兼容) */
    private String specInfo;

    /** 前端展示单价 (单商品下单兼容，仅校验比价使用) */
    private BigDecimal price;

    /** 前端展示小计 (单商品下单兼容，仅校验比价使用) */
    private BigDecimal subtotal;

    /** 多商品明细列表 (购物车批量下单) */
    private List<PrecheckItemDTO> items;

    /** 顾客下单备注 */
    private String remark;

    /** 页面展示的商品总额快照 (元)，可选 */
    private BigDecimal goodsAmount;

    /** 页面展示的配送费/快递费快照 (元)，可选 */
    private BigDecimal deliveryFee;

    /** 页面展示的实付合计快照 (元)，可选 */
    private BigDecimal payAmount;

    @Data
    public static class PrecheckItemDTO {
        /** 商品ID */
        private String productId;

        /** 购买数量/份数 */
        private Integer quantity;

        /** 所选规格信息 */
        private String specInfo;

        /** 前端购物车展示单价 (元) */
        private BigDecimal price;

        /** 前端购物车展示小计 (元) */
        private BigDecimal subtotal;
    }
}
