package com.freshmeat.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.freshmeat.common.PageResult;
import com.freshmeat.dto.OrderCreateDTO;
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
