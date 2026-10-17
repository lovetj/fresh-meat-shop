package com.freshmeat.dto;

import com.freshmeat.entity.OrderItem;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String id;
    private String orderNo;
    private String shopId;
    private String shopName;
    private String userId;
    private Integer deliveryType;
    private String deliveryTypeDesc;

    private Integer status;
    private String statusDesc;

    private Integer payType;
    private String payTypeDesc;
    private Integer payStatus;
    private String payStatusDesc;

    private String receiverName;
    private String receiverPhone;
    private String receiverAddress;
    private BigDecimal receiverLng;
    private BigDecimal receiverLat;
    private BigDecimal deliveryDistance;
    private BigDecimal deliveryFee;

    private BigDecimal goodsAmount;
    private BigDecimal payAmount;

    private String remark;
    private String cancelReason;
    private String rejectReason;

    private LocalDateTime payTime;
    private LocalDateTime acceptTime;
    private LocalDateTime deliverTime;
    private LocalDateTime finishTime;
    private LocalDateTime cancelTime;
    private LocalDateTime createTime;

    /** 商品明细列表 */
    private List<OrderItem> items;

    /** 前端按钮控制标记 */
    private Boolean canPay;        // 顾客可支付
    private Boolean canCancel;     // 顾客可取消
    private Boolean canConfirm;    // 顾客可确认收货
    private Boolean canAccept;     // 商家可接单
    private Boolean canReject;     // 商家可拒单
    private Boolean canDeliver;    // 商家可发货/通知自取
    private Boolean canFinish;     // 商家可完成核销
}
