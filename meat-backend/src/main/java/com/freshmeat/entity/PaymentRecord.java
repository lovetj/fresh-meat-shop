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
@TableName("payment_record")
public class PaymentRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 系统流水单号 */
    private String paymentNo;

    /** 订单ID */
    private String orderId;

    /** 订单号 */
    private String orderNo;

    /** 支付用户ID */
    private String userId;

    /** 支付方式: 1-微信线上支付, 2-货到付款 */
    private Integer payType;

    /** 交易金额 */
    private BigDecimal amount;

    /** 微信交易单号 */
    private String tradeNo;

    /** 微信预支付 prepay_id */
    private String prepayId;

    /** 支付状态: 0待支付 1支付成功 2支付失败 3已退款 */
    private Integer status;

    /** 回调原始报文 */
    private String callbackContent;

    /** 支付时间 */
    private LocalDateTime payTime;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
