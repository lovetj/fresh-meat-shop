package com.freshmeat.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.freshmeat.common.PageResult;
import com.freshmeat.dto.OrderCreateDTO;
import com.freshmeat.dto.OrderPrecheckDTO;
import com.freshmeat.dto.OrderQueryDTO;
import com.freshmeat.dto.OrderStatusUpdateDTO;
import com.freshmeat.dto.OrderVO;
import com.freshmeat.entity.OrderInfo;

public interface OrderService extends IService<OrderInfo> {

    /**
     * 顾客直接下单 (自取 / 商家配送 / 距离校验 / 扣减库存)
     */
    OrderVO createOrder(OrderCreateDTO dto);

    /**
     * 下单前统一校验（只读，不写库）
     *
     * <p>一次性校验：商品是否已删除/下架、分类是否已删除/停用、门店是否歇业、配送范围/起送价是否满足、
     * 商品价格与规格是否与页面一致、期望送达时间是否满足、配送费/快递费与实付金额是否正确。</p>
     *
     * @param dto 下单参数 + 页面展示快照
     * @return 校验结论（canOrder=false 时 message 可直接提示顾客）
     */
    com.freshmeat.dto.OrderPrecheckVO precheck(OrderPrecheckDTO dto);

    /**
     * 顾客端：查询我的订单列表 (分页、状态筛选)
     */
    PageResult<OrderVO> pageCustomerOrders(OrderQueryDTO queryDTO);

    /**
     * 商家端：查询店铺全部订单 (分页、状态筛选)
     */
    PageResult<OrderVO> pageAdminOrders(OrderQueryDTO queryDTO);

    /**
     * 查询订单详情
     */
    OrderVO getOrderDetail(String orderId);

    /**
     * 顾客主动取消订单 (待支付或待接单时可取消，回退库存)
     */
    boolean cancelOrder(String orderId, OrderStatusUpdateDTO dto);

    /**
     * 商家接单 (待接单 -> 待配送/待自提)
     */
    boolean acceptOrder(String orderId);

    /**
     * 商家拒单 (待接单 -> 已拒单，回退库存，退款)
     */
    boolean rejectOrder(String orderId, OrderStatusUpdateDTO dto);

    /**
     * 商家发货/通知自取 (待配送 -> 配送中)
     */
    boolean deliverOrder(String orderId);

    /**
     * 商家快递发货 (快递配送订单：填写快递公司与单号后流转为配送中)
     */
    boolean deliverByExpress(String orderId, com.freshmeat.dto.OrderExpressDTO dto);

    /**
     * 确认收货/核销完成 (配送中/待自提 -> 已完成)
     */
    boolean finishOrder(String orderId);

    /**
     * 商家端：获取订单状态数量统计 (待接单、待配货自提、配送中等计数)
     */
    com.freshmeat.dto.OrderStatusCountVO getAdminStatusCounts(String shopId);

    /**
     * 顾客端：获取当前用户各状态订单数量统计
     */
    com.freshmeat.dto.OrderStatusCountVO getCustomerStatusCounts();

    /**
     * 商家端：自提提货码一键核销
     */
    OrderVO verifyPickupByCode(com.freshmeat.dto.OrderPickupVerifyDTO dto);

    /**
     * 商家端：待支付订单改价 (如称重后金额调整)
     */
    boolean changeOrderPrice(String orderId, com.freshmeat.dto.OrderPriceChangeDTO dto);

    /**
     * 商家端：掌柜主动取消订单并原路退款 (如协商一致退款)
     */
    boolean adminCancelOrder(String orderId, OrderStatusUpdateDTO dto);
}
