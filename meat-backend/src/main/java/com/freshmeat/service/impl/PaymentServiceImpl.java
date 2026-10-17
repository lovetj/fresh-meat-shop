package com.freshmeat.service.impl;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.freshmeat.config.WechatProperties;
import com.freshmeat.dto.WxPayParamsVO;
import com.freshmeat.entity.OrderInfo;
import com.freshmeat.entity.PaymentRecord;
import com.freshmeat.entity.User;
import com.freshmeat.exception.BusinessException;
import com.freshmeat.mapper.OrderInfoMapper;
import com.freshmeat.mapper.PaymentRecordMapper;
import com.freshmeat.mapper.UserMapper;
import com.freshmeat.service.PaymentService;
import com.freshmeat.util.SecurityContextUtil;
import com.freshmeat.util.WechatPayUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.security.PrivateKey;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentServiceImpl extends ServiceImpl<PaymentRecordMapper, PaymentRecord> implements PaymentService {

    private final OrderInfoMapper orderMapper;
    private final UserMapper userMapper;
    private final WechatProperties wechatProperties;

    private String generatePaymentNo() {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        int rand = (int) ((Math.random() * 9 + 1) * 1000);
        return "P" + ts + rand;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WxPayParamsVO createWxPrepay(String orderId) {
        String currentUserId = SecurityContextUtil.getUserId();
        if (currentUserId == null) {
            throw new BusinessException(401, "请先登录");
        }

        // 1. 校验订单
        OrderInfo order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException("订单不存在");
        }
        if (!order.getUserId().equals(currentUserId)) {
            throw new BusinessException(403, "无权操作该订单");
        }
        if (order.getStatus() != OrderInfo.STATUS_PENDING_PAY) {
            throw new BusinessException("当前订单状态不可发起支付");
        }
        if (order.getPayType() != OrderInfo.PAY_TYPE_WECHAT) {
            throw new BusinessException("该订单支付方式非微信支付");
        }

        User user = userMapper.selectById(currentUserId);
        String openid = user != null ? user.getOpenid() : "";

        // 2. 检查或生成支付流水
        String paymentNo = generatePaymentNo();
        PaymentRecord record = PaymentRecord.builder()
                .paymentNo(paymentNo)
                .orderId(order.getId())
                .orderNo(order.getOrderNo())
                .userId(currentUserId)
                .payType(OrderInfo.PAY_TYPE_WECHAT)
                .amount(order.getPayAmount())
                .status(0) // 待支付
                .build();
        this.save(record);

        // 3. 判断是否开启 Mock 支付
        boolean isMock = Boolean.TRUE.equals(wechatProperties.getPay().getMock());
        if (isMock) {
            log.info("【微信支付】当前为 Mock 模拟支付模式，生成模拟支付参数: orderNo={}", order.getOrderNo());
            String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
            String nonceStr = UUID.randomUUID().toString().replace("-", "");
            String prepayId = "wx_prepay_mock_" + System.currentTimeMillis();

            record.setPrepayId(prepayId);
            this.updateById(record);

            return WxPayParamsVO.builder()
                    .orderId(order.getId())
                    .orderNo(order.getOrderNo())
                    .payAmount(order.getPayAmount())
                    .isMock(true)
                    .timeStamp(timestamp)
                    .nonceStr(nonceStr)
                    .packageVal("prepay_id=" + prepayId)
                    .signType("RSA")
                    .paySign("MOCK_PAY_SIGN_" + nonceStr)
                    .build();
        }

        // 4. 真实微信支付 V3 JSAPI 统一下单
        try {
            return callRealWechatPay(order, openid, record);
        } catch (Exception e) {
            log.error("调用微信统一下单失败: {}", e.getMessage(), e);
            throw new BusinessException("微信下单发起失败: " + e.getMessage());
        }
    }

    private WxPayParamsVO callRealWechatPay(OrderInfo order, String openid, PaymentRecord record) throws Exception {
        WechatProperties.Pay payConfig = wechatProperties.getPay();
        String appId = wechatProperties.getMiniapp().getAppid();
        String mchId = payConfig.getMchid();

        PrivateKey privateKey = WechatPayUtil.loadPrivateKey(payConfig.getPrivateKeyPath());

        // 构造请求体 (金额单位: 分)
        int totalFen = order.getPayAmount().multiply(new BigDecimal(100)).intValue();
        Map<String, Object> reqBody = new HashMap<>();
        reqBody.put("appid", appId);
        reqBody.put("mchid", mchId);
        reqBody.put("description", "维琼鲜肉铺-肉类生鲜订单");
        reqBody.put("out_trade_no", record.getPaymentNo());
        reqBody.put("notify_url", payConfig.getNotifyUrl());

        Map<String, Object> amount = new HashMap<>();
        amount.put("total", totalFen);
        amount.put("currency", "CNY");
        reqBody.put("amount", amount);

        Map<String, Object> payer = new HashMap<>();
        payer.put("openid", openid);
        reqBody.put("payer", payer);

        String jsonBody = JSON.toJSONString(reqBody);
        String canonicalUrl = "/v3/pay/transactions/jsapi";
        String authHeader = WechatPayUtil.buildAuthorizationHeader("POST", canonicalUrl, jsonBody,
                mchId, payConfig.getCertSerialNo(), privateKey);

        HttpResponse response = HttpRequest.post("https://api.mch.weixin.qq.com" + canonicalUrl)
                .header("Authorization", authHeader)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .body(jsonBody)
                .execute();

        String resBody = response.body();
        JSONObject resJson = JSON.parseObject(resBody);
        if (response.getStatus() != 200 || !resJson.containsKey("prepay_id")) {
            log.error("微信下单接口返回错误: code={}, body={}", response.getStatus(), resBody);
            throw new BusinessException("微信统一下单失败: " + (resJson.containsKey("message") ? resJson.getString("message") : resBody));
        }

        String prepayId = resJson.getString("prepay_id");
        record.setPrepayId(prepayId);
        this.updateById(record);

        // 生成前端小程序拉起支付参数
        String timeStamp = String.valueOf(System.currentTimeMillis() / 1000);
        String nonceStr = UUID.randomUUID().toString().replace("-", "");
        String packageVal = "prepay_id=" + prepayId;
        String paySign = WechatPayUtil.buildMiniappPaySign(appId, timeStamp, nonceStr, packageVal, privateKey);

        return WxPayParamsVO.builder()
                .orderId(order.getId())
                .orderNo(order.getOrderNo())
                .payAmount(order.getPayAmount())
                .isMock(false)
                .timeStamp(timeStamp)
                .nonceStr(nonceStr)
                .packageVal(packageVal)
                .signType("RSA")
                .paySign(paySign)
                .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean mockPaySuccess(String orderId) {
        OrderInfo order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException("订单不存在");
        }
        if (order.getStatus() != OrderInfo.STATUS_PENDING_PAY) {
            throw new BusinessException("订单不是待支付状态");
        }

        LocalDateTime now = LocalDateTime.now();

        // 1. 更新支付流水
        PaymentRecord record = this.getOne(new LambdaQueryWrapper<PaymentRecord>()
                .eq(PaymentRecord::getOrderId, orderId)
                .orderByDesc(PaymentRecord::getCreateTime)
                .last("LIMIT 1"));
        if (record != null) {
            record.setStatus(1); // 成功
            record.setTradeNo("MOCK_TXN_" + System.currentTimeMillis());
            record.setPayTime(now);
            record.setCallbackContent("{\"result\":\"MOCK_PAY_SUCCESS\"}");
            this.updateById(record);
        }

        // 2. 状态机流转：待支付(0) -> 待接单(1)
        order.setStatus(OrderInfo.STATUS_PENDING_ACCEPT);
        order.setPayStatus(1); // 已支付
        order.setPayTime(now);
        orderMapper.updateById(order);

        log.info("【Mock支付成功】订单 orderNo={} 已流转为待接单状态", order.getOrderNo());
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String handleWxNotify(String notifyJson) {
        log.info("收到微信支付 V3 回调报文: {}", notifyJson);
        try {
            JSONObject root = JSON.parseObject(notifyJson);
            if (!"TRANSACTION.SUCCESS".equals(root.getString("event_type"))) {
                return "{\"code\":\"FAIL\",\"message\":\"非支付成功通知\"}";
            }
            JSONObject resource = root.getJSONObject("resource");
            String ciphertext = resource.getString("ciphertext");
            String nonce = resource.getString("nonce");
            String associatedData = resource.getString("associated_data");

            String decrypted = WechatPayUtil.decryptToString(associatedData, nonce, ciphertext,
                    wechatProperties.getPay().getApiV3Key());
            log.info("微信支付回调解密明文: {}", decrypted);

            JSONObject trade = JSON.parseObject(decrypted);
            String outTradeNo = trade.getString("out_trade_no");
            String transactionId = trade.getString("transaction_id");
            String tradeState = trade.getString("trade_state");

            if ("SUCCESS".equalsIgnoreCase(tradeState)) {
                PaymentRecord record = this.getOne(new LambdaQueryWrapper<PaymentRecord>()
                        .eq(PaymentRecord::getPaymentNo, outTradeNo));
                if (record != null && record.getStatus() == 0) {
                    record.setStatus(1);
                    record.setTradeNo(transactionId);
                    record.setPayTime(LocalDateTime.now());
                    record.setCallbackContent(decrypted);
                    this.updateById(record);

                    OrderInfo order = orderMapper.selectById(record.getOrderId());
                    if (order != null && order.getStatus() == OrderInfo.STATUS_PENDING_PAY) {
                        order.setStatus(OrderInfo.STATUS_PENDING_ACCEPT); // 待接单
                        order.setPayStatus(1); // 已支付
                        order.setPayTime(LocalDateTime.now());
                        orderMapper.updateById(order);
                    }
                }
            }
            return "{\"code\":\"SUCCESS\",\"message\":\"成功\"}";
        } catch (Exception e) {
            log.error("处理微信支付回调异常: {}", e.getMessage(), e);
            return "{\"code\":\"FAIL\",\"message\":\"" + e.getMessage() + "\"}";
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void processRefund(OrderInfo order, String reason) {
        if (order.getPayStatus() != 1) {
            return;
        }
        log.info("执行订单退款: orderNo={}, reason={}", order.getOrderNo(), reason);
        order.setPayStatus(2); // 已退款
        orderMapper.updateById(order);

        PaymentRecord record = this.getOne(new LambdaQueryWrapper<PaymentRecord>()
                .eq(PaymentRecord::getOrderId, order.getId())
                .orderByDesc(PaymentRecord::getCreateTime)
                .last("LIMIT 1"));
        if (record != null) {
            record.setStatus(3); // 已退款
            this.updateById(record);
        }
    }
}
