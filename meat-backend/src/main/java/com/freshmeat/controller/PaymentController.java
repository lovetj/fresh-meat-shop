package com.freshmeat.controller;

import com.freshmeat.annotation.RequireLogin;
import com.freshmeat.common.Result;
import com.freshmeat.dto.WxPayParamsVO;
import com.freshmeat.service.PaymentService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Api(tags = "微信支付与流水模块")
@Slf4j
@RestController
@RequestMapping("/payment")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    /**
     * 发起微信线上支付 (生成前端调起 wx.requestPayment 所需签名参数)
     */
    @ApiOperation(value = "发起微信线上支付", notes = "生成小程序原生调起支付所需的timeStamp、nonceStr、package、paySign等参数")
    @RequireLogin
    @PostMapping("/wx/prepay/{orderId}")
    public Result<WxPayParamsVO> createWxPrepay(@PathVariable String orderId) {
        WxPayParamsVO vo = paymentService.createWxPrepay(orderId);
        return Result.success("预支付下单成功", vo);
    }

    /**
     * 模拟支付成功接口 (本地开发测试与演示无需申请真实微信商户号即可闭环体验)
     */
    @ApiOperation(value = "本地Mock模拟支付成功", notes = "在无真实微信商户号证书时，一键触发支付成功回调，推动订单流转至待接单")
    @RequireLogin
    @PostMapping("/mock/success/{orderId}")
    public Result<Boolean> mockPaySuccess(@PathVariable String orderId) {
        boolean ok = paymentService.mockPaySuccess(orderId);
        return Result.success("模拟支付成功，订单已进入待接单状态", ok);
    }

    /**
     * 微信支付 V3 异步回调通知
     */
    @ApiOperation(value = "微信支付V3回调通知", notes = "接收微信官方支付结果通知，执行解密验签与状态更新")
    @PostMapping("/wx/notify")
    public String handleWxNotify(@RequestBody String notifyJson) {
        return paymentService.handleWxNotify(notifyJson);
    }
}
