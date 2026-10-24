package com.freshmeat.controller;

import com.freshmeat.annotation.RequireAdmin;
import com.freshmeat.common.PageResult;
import com.freshmeat.common.Result;
import com.freshmeat.dto.OrderCreateDTO;
import com.freshmeat.dto.OrderQueryDTO;
import com.freshmeat.dto.OrderStatusUpdateDTO;
import com.freshmeat.dto.OrderVO;
import com.freshmeat.service.OrderService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Api(tags = "订单管理与状态流转模块")
@RestController
@RequestMapping("/order")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    /**
     * 顾客端：直接下单购买 (自取 / 商家配送)
     */
    @ApiOperation(value = "直接下单", notes = "无需购物车，选定商品与切法直接下单，自动进行配送测距与库存检查")
    @PostMapping("/create")
    public Result<OrderVO> createOrder(@RequestBody @Validated OrderCreateDTO dto) {
        OrderVO orderVO = orderService.createOrder(dto);
        return Result.success("下单成功", orderVO);
    }

    /**
     * 顾客端：查询我的订单列表
     */
    @ApiOperation(value = "我的订单列表 (顾客端)", notes = "根据状态筛选并分页查看订单")
    @GetMapping("/list")
    public Result<PageResult<OrderVO>> list(OrderQueryDTO queryDTO) {
        return Result.success(orderService.pageCustomerOrders(queryDTO));
    }

    /**
     * 顾客端：我的订单各状态数量统计
     */
    @ApiOperation(value = "我的订单各状态数量统计", notes = "用于订单列表顶部状态标签角标")
    @GetMapping("/status-counts")
    public Result<com.freshmeat.dto.OrderStatusCountVO> customerStatusCounts() {
        return Result.success(orderService.getCustomerStatusCounts());
    }

    /**
     * 顾客端与商家端：查询订单详情
     */
    @ApiOperation(value = "查询订单详情", notes = "返回商品清单、配送信息、实付金额及状态流转信息")
    @GetMapping("/detail/{id}")
    public Result<OrderVO> detail(@PathVariable String id) {
        return Result.success(orderService.getOrderDetail(id));
    }

    /**
     * 顾客端：主动取消订单
     */
    @ApiOperation(value = "顾客主动取消订单", notes = "仅允许在未接单前取消，已支付则原路退款并回退库存")
    @PostMapping("/cancel/{id}")
    public Result<Boolean> cancel(@PathVariable String id, @RequestBody(required = false) OrderStatusUpdateDTO dto) {
        boolean ok = orderService.cancelOrder(id, dto);
        return Result.success("订单已取消", ok);
    }

    /**
     * 顾客端：确认收货
     */
    @ApiOperation(value = "顾客确认收货", notes = "顾客收货完成，订单流转为已完成(4)")
    @PostMapping("/confirm/{id}")
    public Result<Boolean> confirm(@PathVariable String id) {
        boolean ok = orderService.finishOrder(id);
        return Result.success("已确认收货", ok);
    }

    /**
     * 商家端：订单列表 (支持全部/待接单/待配送/待自取/已完成)
     */
    @ApiOperation(value = "掌柜订单管理列表", notes = "掌柜按状态筛选待接单、待发货自提、配送中等全部订单")
    @RequireAdmin
    @GetMapping("/admin/list")
    public Result<PageResult<OrderVO>> adminList(OrderQueryDTO queryDTO) {
        return Result.success(orderService.pageAdminOrders(queryDTO));
    }

    /**
     * 商家端：接单
     */
    @ApiOperation(value = "掌柜立即接单", notes = "状态从待接单(1)流转为待配送/待自取(2)")
    @RequireAdmin
    @PostMapping("/admin/accept/{id}")
    public Result<Boolean> accept(@PathVariable String id) {
        boolean ok = orderService.acceptOrder(id);
        return Result.success("已接单", ok);
    }

    /**
     * 商家端：拒单
     */
    @ApiOperation(value = "掌柜拒单", notes = "填写拒单原因，已付款原路退回并回退库存")
    @RequireAdmin
    @PostMapping("/admin/reject/{id}")
    public Result<Boolean> reject(@PathVariable String id, @RequestBody(required = false) OrderStatusUpdateDTO dto) {
        boolean ok = orderService.rejectOrder(id, dto);
        return Result.success("已拒单", ok);
    }

    /**
     * 商家端：开始配送 / 备货完成通知
     */
    @ApiOperation(value = "掌柜开始配送/通知自提", notes = "更新为配送中(3)或已备齐通知")
    @RequireAdmin
    @PostMapping("/admin/deliver/{id}")
    public Result<Boolean> deliver(@PathVariable String id) {
        boolean ok = orderService.deliverOrder(id);
        return Result.success("已发货/通知自取", ok);
    }

    /**
     * 商家端：快递配送发货（填写快递公司与快递单号）
     */
    @ApiOperation(value = "掌柜填写快递单号发货", notes = "仅快递配送且已接单的订单可用，发货后流转为配送中(3)")
    @RequireAdmin
    @PostMapping("/admin/express/{id}")
    public Result<Boolean> deliverByExpress(@PathVariable String id, @RequestBody @Validated com.freshmeat.dto.OrderExpressDTO dto) {
        boolean ok = orderService.deliverByExpress(id, dto);
        return Result.success("快递已发出", ok);
    }

    /**
     * 商家端：完成订单 / 核销
     */
    @ApiOperation(value = "掌柜到店核销/确认送达", notes = "订单流转为已完成(4)")
    @RequireAdmin
    @PostMapping("/admin/finish/{id}")
    public Result<Boolean> finish(@PathVariable String id) {
        boolean ok = orderService.finishOrder(id);
        return Result.success("订单已完成", ok);
    }

    /**
     * 商家端：订单状态统计数量 (待接单、待配货自提、配送中等计数)
     */
    @ApiOperation(value = "掌柜订单各状态数量统计", notes = "返回待接单、待自提、配送中、售后等角标统计")
    @RequireAdmin
    @GetMapping("/admin/status-counts")
    public Result<com.freshmeat.dto.OrderStatusCountVO> statusCounts(@RequestParam(required = false) String shopId) {
        return Result.success(orderService.getAdminStatusCounts(shopId));
    }

    /**
     * 商家端：提货码一键核销
     */
    @ApiOperation(value = "自提提货码核销", notes = "输入顾客6位提货码一键核销完成")
    @RequireAdmin
    @PostMapping("/admin/verify-pickup")
    public Result<OrderVO> verifyPickup(@RequestBody @Validated com.freshmeat.dto.OrderPickupVerifyDTO dto) {
        return Result.success("核销成功", orderService.verifyPickupByCode(dto));
    }

    /**
     * 商家端：待支付订单改价
     */
    @ApiOperation(value = "掌柜改价", notes = "生鲜称重后调整实付金额(仅未支付)")
    @RequireAdmin
    @PostMapping("/admin/change-price/{id}")
    public Result<Boolean> changePrice(@PathVariable String id, @RequestBody @Validated com.freshmeat.dto.OrderPriceChangeDTO dto) {
        boolean ok = orderService.changeOrderPrice(id, dto);
        return Result.success("改价成功", ok);
    }

    /**
     * 商家端：主动取消订单并退款
     */
    @ApiOperation(value = "掌柜主动取消订单", notes = "协商一致取消并退款")
    @RequireAdmin
    @PostMapping("/admin/cancel/{id}")
    public Result<Boolean> adminCancel(@PathVariable String id, @RequestBody(required = false) OrderStatusUpdateDTO dto) {
        boolean ok = orderService.adminCancelOrder(id, dto);
        return Result.success("订单已取消退款", ok);
    }
}
