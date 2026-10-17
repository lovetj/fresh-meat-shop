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
public class WxPayParamsVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 业务订单ID */
    private String orderId;

    /** 订单编号 */
    private String orderNo;

    /** 支付金额 (元) */
    private BigDecimal payAmount;

    /** 是否为模拟支付模式 */
    private Boolean isMock;

    // ----- 微信小程序原生支付入参 (wx.requestPayment) -----
    /** 时间戳 (秒级字符串) */
    private String timeStamp;

    /** 随机字符串 */
    private String nonceStr;

    /** 统一下单接口返回的 prepay_id 参数值，格式为 prepay_id=*** */
    private String packageVal;

    /** 签名类型，微信官方默认为 RSA */
    private String signType;

    /** 签名 */
    private String paySign;
}
