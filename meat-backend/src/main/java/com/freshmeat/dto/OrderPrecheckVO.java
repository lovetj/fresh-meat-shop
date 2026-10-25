package com.freshmeat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * 下单前统一校验结果
 *
 * <p>接口始终返回 200，校验结论放在 {@link #canOrder} 中：
 * 前端只需判断 canOrder 并按 {@link #message} 提示，即可覆盖商品/分类/门店/配送范围/价格/规格/运费等全部场景。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderPrecheckVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /* ============ 校验项编码（前端可按 code 做差异化处理） ============ */

    /** 未选择任何商品 */
    public static final String CODE_NO_ITEM = "NO_ITEM";
    /** 配送方式不支持 */
    public static final String CODE_DELIVERY_TYPE_INVALID = "DELIVERY_TYPE_INVALID";
    /** 配送通道未开通（如外卖） */
    public static final String CODE_DELIVERY_CHANNEL_CLOSED = "DELIVERY_CHANNEL_CLOSED";
    /** 未选择支付方式 / 支付方式非法 */
    public static final String CODE_PAY_TYPE_INVALID = "PAY_TYPE_INVALID";
    /** 当前配送方式未开通所选支付方式 */
    public static final String CODE_PAY_TYPE_UNAVAILABLE = "PAY_TYPE_UNAVAILABLE";
    /** 商品已被删除 */
    public static final String CODE_PRODUCT_DELETED = "PRODUCT_DELETED";
    /** 商品已下架 */
    public static final String CODE_PRODUCT_OFF_SALE = "PRODUCT_OFF_SALE";
    /** 商品库存不足 */
    public static final String CODE_STOCK_NOT_ENOUGH = "STOCK_NOT_ENOUGH";
    /** 商品所属分类已被删除 */
    public static final String CODE_CATEGORY_DELETED = "CATEGORY_DELETED";
    /** 商品所属分类已停用 */
    public static final String CODE_CATEGORY_DISABLED = "CATEGORY_DISABLED";
    /** 商品不属于同一门店 */
    public static final String CODE_WRONG_SHOP = "WRONG_SHOP";
    /** 门店不存在 */
    public static final String CODE_SHOP_NOT_FOUND = "SHOP_NOT_FOUND";
    /** 门店已歇业 */
    public static final String CODE_SHOP_CLOSED = "SHOP_CLOSED";
    /** 未选择收货地址 */
    public static final String CODE_ADDRESS_REQUIRED = "ADDRESS_REQUIRED";
    /** 收货地址无效 */
    public static final String CODE_ADDRESS_INVALID = "ADDRESS_INVALID";
    /** 超出配送范围 / 不支持该地址配送 */
    public static final String CODE_DELIVERY_NOT_SUPPORTED = "DELIVERY_NOT_SUPPORTED";
    /** 未达到起送金额 */
    public static final String CODE_MIN_ORDER_NOT_REACHED = "MIN_ORDER_NOT_REACHED";
    /** 期望送达/自取时间不满足 */
    public static final String CODE_EXPECTED_TIME_INVALID = "EXPECTED_TIME_INVALID";
    /** 商品价格已变更 */
    public static final String CODE_PRICE_CHANGED = "PRICE_CHANGED";
    /** 商品规格已变更/失效 */
    public static final String CODE_SPEC_CHANGED = "SPEC_CHANGED";
    /** 配送费/快递费不正确 */
    public static final String CODE_DELIVERY_FEE_CHANGED = "DELIVERY_FEE_CHANGED";
    /** 订单金额已变化 */
    public static final String CODE_AMOUNT_CHANGED = "AMOUNT_CHANGED";

    /** 是否允许下单：false 时前端应阻断提交并按 message 提示 */
    private Boolean canOrder;

    /** 首个阻断原因编码（canOrder=true 时为 null） */
    private String code;

    /** 首个阻断提示文案（canOrder=true 时返回「校验通过」） */
    private String message;

    /** 全部校验问题（按严重程度排序，第一条即 message） */
    private List<Issue> issues;

    /** 商品逐项校验结果（含数据库当前价格/库存，可用于刷新前端购物车） */
    private List<ItemResult> items;

    /** 商品价格是否与页面展示不一致 */
    private Boolean priceChanged;

    /** 商品规格是否与页面展示不一致/已失效 */
    private Boolean specChanged;

    /** 配送费/快递费是否与页面展示不一致 */
    private Boolean deliveryFeeChanged;

    /** 服务端重算的商品金额 (元) */
    private BigDecimal goodsAmount;

    /** 服务端重算的配送费/快递费 (元) */
    private BigDecimal deliveryFee;

    /** 服务端重算的实付金额 (元) */
    private BigDecimal payAmount;

    /** 当前配送距离对应的起送金额 (元) */
    private BigDecimal minOrderAmount;

    /** 距起送金额还差多少 (元)，已满足为 0 */
    private BigDecimal shortAmount;

    /** 配送距离 (km)，自取/快递为 0 */
    private Double distance;

    /** 是否在配送范围内 */
    private Boolean canDeliver;

    /** 校验通过的期望送达时间 (yyyy-MM-dd HH:mm) */
    private String expectedTime;

    /** 门店实际展示状态: 1-营业中, 2-休息中, 3-歇业中 */
    private Integer shopStatus;

    /** 门店实际展示状态文案 */
    private String shopStatusText;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Issue implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 校验项编码，见 OrderPrecheckVO 常量 */
        private String code;

        /** 可直接展示给顾客的提示文案 */
        private String message;

        /** 关联商品ID（商品级问题才有值） */
        private String productId;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ItemResult implements Serializable {

        private static final long serialVersionUID = 1L;

        private String productId;

        private String productName;

        private String coverImage;

        /** 提交的规格信息 */
        private String specInfo;

        /** 提交的购买数量 */
        private Integer quantity;

        /** 数据库当前单价 (元) */
        private BigDecimal price;

        /** 页面快照单价 (元) */
        private BigDecimal clientPrice;

        /** 数据库当前单价 × 数量 */
        private BigDecimal subtotal;

        /** 当前剩余库存 */
        private Integer stock;

        /** 是否上架 */
        private Boolean onSale;

        /** 商品所属分类ID */
        private String categoryId;

        /** 商品所属分类是否有效（存在且启用） */
        private Boolean categoryValid;

        /** 价格是否与页面快照一致 */
        private Boolean priceMatched;

        /** 规格是否与商品配置一致 */
        private Boolean specMatched;

        /** 该商品是否可继续购买（false 时前端应从购物车移除） */
        private Boolean purchasable;

        /** 该明细是否整体校验通过 */
        private Boolean valid;

        /** 不通过原因 */
        private String invalidReason;
    }
}
