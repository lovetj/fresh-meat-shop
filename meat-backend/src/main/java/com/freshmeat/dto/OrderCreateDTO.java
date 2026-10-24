package com.freshmeat.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;
import java.util.List;

@Data
public class OrderCreateDTO {

    /** 目标商铺ID (可选，若不传则自动取商品所属店铺) */
    private String shopId;

    /** 配送方式: 1-自取, 2-商家配送, 3-外卖(保留入口), 4-快递配送 */
    @NotNull(message = "请选择配送方式")
    private Integer deliveryType;

    /** 支付方式: 1-微信线上支付, 2-货到付款 */
    @NotNull(message = "请选择支付方式")
    private Integer payType;

    /** 商家配送时的收货地址ID */
    private String addressId;

    /** 自取人姓名 (自提时可选) */
    private String pickupName;

    /** 自取人联系电话 (自提时可选) */
    private String pickupPhone;

    /** 购买商品ID (单商品下单兼容) */
    private String productId;

    /** 购买数量/份数 (单商品下单兼容) */
    private Integer quantity;

    /** 所选规格信息 (如: 500g(约1斤) / 切小块) (单商品下单兼容) */
    private String specInfo;

    /** 多商品明细列表 (支持购物车批量下单) */
    private List<OrderItemDTO> items;

    /** 顾客下单备注 */
    private String remark;

    @Data
    public static class OrderItemDTO {
        /** 商品ID */
        private String productId;

        /** 购买数量/份数 */
        private Integer quantity;

        /** 所选规格信息 */
        private String specInfo;
    }
}
