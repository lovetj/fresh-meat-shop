package com.freshmeat.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.freshmeat.dto.WxPayParamsVO;
import com.freshmeat.entity.OrderInfo;
import com.freshmeat.entity.PaymentRecord;

public interface PaymentService extends IService<PaymentRecord> {

    /**
     * 创建微信小程序预支付交易 (生成前端 wx.requestPayment 所需签名参数)
     */
    WxPayParamsVO createWxPrepay(String orderId);

    /**
     * 模拟微信支付成功 (用于开发/测试环境快捷体验)
     */
    boolean mockPaySuccess(String orderId);

    /**
     * 处理微信支付 V3 异步回调通知
     */
    String handleWxNotify(String notifyJson);

    /**
     * 处理退款 (取消订单或商家拒单时触发)
     */
    void processRefund(OrderInfo order, String reason);
}
