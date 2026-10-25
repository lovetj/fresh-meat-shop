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
@TableName("order_info")
public class OrderInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 订单状态常量 */
    public static final int STATUS_PENDING_PAY = 0;      // 待支付
    public static final int STATUS_PENDING_ACCEPT = 1;   // 待接单
    public static final int STATUS_ACCEPTED = 2;         // 已接单(待配送/待自取)
    public static final int STATUS_DELIVERING = 3;       // 配送中
    public static final int STATUS_COMPLETED = 4;        // 已完成
    public static final int STATUS_CANCELLED = 5;        // 已取消
    public static final int STATUS_REJECTED = 6;         // 已拒单

    /** 配送方式常量 */
    public static final int DELIVERY_PICKUP = 1;         // 自取
    public static final int DELIVERY_MERCHANT = 2;       // 商家配送
    public static final int DELIVERY_TAKEOUT = 3;        // 外卖(暂保留入口)
    public static final int DELIVERY_EXPRESS = 4;        // 快递配送(掌柜接单后手动填写快递单号)

    /** 支付方式 */
    public static final int PAY_TYPE_WECHAT = 1;         // 微信支付
    public static final int PAY_TYPE_COD = 2;            // 货到付款

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 唯一订单编号 */
    private String orderNo;

    /** 订单归属商铺UUID */
    private String shopId;

    /** 下单顾客ID */
    private String userId;

    /** 配送方式: 1-自取, 2-商家配送, 3-外卖, 4-快递配送 */
    private Integer deliveryType;

    /** 快递公司名称 (快递配送订单发货时由掌柜填写) */
    private String expressCompany;

    /** 快递单号 (快递配送订单发货时由掌柜填写) */
    private String expressNo;

    /** 订单状态: 0待支付 1待接单 2待配送/待自取 3配送中 4已完成 5已取消 6已拒单 */
    private Integer status;

    /** 支付方式: 1-微信线上支付, 2-货到付款 */
    private Integer payType;

    /** 支付状态: 0-未支付, 1-已支付, 2-已退款 */
    private Integer payStatus;

    /** 收货人/自取人姓名 */
    private String receiverName;

    /** 联系电话 */
    private String receiverPhone;

    /** 配送详细地址 */
    private String receiverAddress;

    /** 收货地址经度 */
    private BigDecimal receiverLng;

    /** 收货地址纬度 */
    private BigDecimal receiverLat;

    /** 实际配送距离(km) */
    private BigDecimal deliveryDistance;

    /** 配送费 */
    private BigDecimal deliveryFee;

    /** 商品总金额 */
    private BigDecimal goodsAmount;

    /** 实付金额 */
    private BigDecimal payAmount;

    /** 改价前原始金额 */
    private BigDecimal originalPayAmount;

    /** 自提核销码(自取订单生成的提货凭证) */
    private String pickupCode;

    /** 顾客期望送达/到店自取时间(下单时选择，按店铺时效配置生成可选范围) */
    private LocalDateTime expectedTime;

    /** 备注要求 */
    private String remark;

    /** 取消原因 */
    private String cancelReason;

    /** 商家拒单原因 */
    private String rejectReason;

    /** 支付时间 */
    private LocalDateTime payTime;

    /** 接单时间 */
    private LocalDateTime acceptTime;

    /** 发货时间 */
    private LocalDateTime deliverTime;

    /** 完成时间 */
    private LocalDateTime finishTime;

    /** 取消时间 */
    private LocalDateTime cancelTime;

    /** 下单时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
