package com.freshmeat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.freshmeat.common.PageResult;
import com.freshmeat.dto.OrderCreateDTO;
import com.freshmeat.dto.OrderQueryDTO;
import com.freshmeat.dto.OrderStatusUpdateDTO;
import com.freshmeat.dto.OrderVO;
import com.freshmeat.entity.*;
import com.freshmeat.exception.BusinessException;
import com.freshmeat.mapper.OrderItemMapper;
import com.freshmeat.mapper.OrderInfoMapper;
import com.freshmeat.mapper.ProductMapper;
import com.freshmeat.mapper.UserAddressMapper;
import com.freshmeat.mapper.UserMapper;
import com.freshmeat.service.OrderService;
import com.freshmeat.service.PaymentService;
import com.freshmeat.service.ShopInfoService;
import com.freshmeat.util.DistanceUtil;
import com.freshmeat.util.SecurityContextUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl extends ServiceImpl<OrderInfoMapper, OrderInfo> implements OrderService {

    private final OrderItemMapper orderItemMapper;
    private final ProductMapper productMapper;
    private final UserAddressMapper userAddressMapper;
    private final UserMapper userMapper;
    private final ShopInfoService shopInfoService;
    private final PaymentService paymentService;

    private String generateOrderNo() {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        int rand = (int) ((Math.random() * 9 + 1) * 1000);
        return "O" + ts + rand;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderVO createOrder(OrderCreateDTO dto) {
        String userId = SecurityContextUtil.getUserId();
        if (userId == null) {
            throw new BusinessException(401, "请先登录");
        }
        User user = userMapper.selectById(userId);

        // 1. 配送方式规则校验
        if (dto.getDeliveryType() == OrderInfo.DELIVERY_TAKEOUT) {
            throw new BusinessException("外卖配送通道暂未开通，仅支持「自取」或「商家配送」");
        }
        if (dto.getDeliveryType() != OrderInfo.DELIVERY_PICKUP && dto.getDeliveryType() != OrderInfo.DELIVERY_MERCHANT) {
            throw new BusinessException("不支持的配送方式");
        }

        // 2. 校验商品与库存
        Product product = productMapper.selectById(dto.getProductId());
        if (product == null) {
            throw new BusinessException("商品不存在");
        }
        if (product.getIsOnSale() == null || product.getIsOnSale() != 1) {
            throw new BusinessException("商品「" + product.getName() + "」已下架");
        }
        if (product.getStock() < dto.getQuantity()) {
            throw new BusinessException("商品「" + product.getName() + "」库存不足，当前剩余: " + product.getStock());
        }

        // 确定下单目标商铺 (优先商品所绑定的店铺，亦兼容前端传参)
        String targetShopId = StringUtils.hasText(dto.getShopId()) ? dto.getShopId() : product.getShopId();
        if (StringUtils.hasText(dto.getShopId()) && StringUtils.hasText(product.getShopId()) && !dto.getShopId().equals(product.getShopId())) {
            throw new BusinessException("所选商品不属于当前切换的店铺，请刷新页面重新选购");
        }

        ShopInfo shop = shopInfoService.getShopInfo(targetShopId);
        if (shop == null) {
            throw new BusinessException("下单店铺不存在");
        }
        if (shop.getStatus() != null && shop.getStatus() == 0) {
            throw new BusinessException("「" + shop.getName() + "」当前正在休息中，暂不接单");
        }

        // 3. 计算商品总额
        BigDecimal goodsAmount = product.getPrice().multiply(new BigDecimal(dto.getQuantity()));
        BigDecimal deliveryFee = BigDecimal.ZERO;
        BigDecimal deliveryDistance = BigDecimal.ZERO;

        String receiverName = "";
        String receiverPhone = "";
        String receiverAddress = "";
        BigDecimal receiverLng = null;
        BigDecimal receiverLat = null;

        // 4. 自取 vs 商家配送处理
        if (dto.getDeliveryType() == OrderInfo.DELIVERY_PICKUP) {
            // 自取
            receiverName = StringUtils.hasText(dto.getPickupName()) ? dto.getPickupName() : (user != null ? user.getNickname() : "到店顾客");
            receiverPhone = StringUtils.hasText(dto.getPickupPhone()) ? dto.getPickupPhone() : (user != null ? user.getPhone() : "");
            receiverAddress = "【自取】" + shop.getAddress();
        } else {
            // 商家配送：校验地址与同城距离
            if (dto.getAddressId() == null) {
                throw new BusinessException("请选择收货地址");
            }
            UserAddress address = userAddressMapper.selectById(dto.getAddressId());
            if (address == null || !address.getUserId().equals(userId)) {
                throw new BusinessException("收货地址不存在");
            }
            if (address.getLongitude() == null || address.getLatitude() == null) {
                throw new BusinessException("该地址缺少经纬度定位，请重新编辑完善");
            }

            // 起送金额校验
            if (shop.getMinOrderAmount() != null && goodsAmount.compareTo(shop.getMinOrderAmount()) < 0) {
                throw new BusinessException(String.format("未达到起送金额(¥%.2f)，当前商品金额为¥%.2f",
                        shop.getMinOrderAmount().doubleValue(), goodsAmount.doubleValue()));
            }

            // 核心业务校验：配送距离限制 (Haversine 算法)
            double actualDist = DistanceUtil.calculateDistance(
                    shop.getLongitude().doubleValue(), shop.getLatitude().doubleValue(),
                    address.getLongitude().doubleValue(), address.getLatitude().doubleValue()
            );
            double maxDist = shop.getMaxDeliveryDistance() != null ? shop.getMaxDeliveryDistance().doubleValue() : 5.0;

            if (actualDist > maxDist) {
                throw new BusinessException(String.format(
                        "超出配送范围！当前地址距店铺约 %.2f 公里，店铺最大配送距离为 %.1f 公里。建议选择「自取」或更换地址。",
                        actualDist, maxDist
                ));
            }

            deliveryDistance = BigDecimal.valueOf(actualDist);
            deliveryFee = shop.getDeliveryFee() != null ? shop.getDeliveryFee() : BigDecimal.ZERO;
            receiverName = address.getContactName();
            receiverPhone = address.getContactPhone();
            receiverAddress = address.getFullAddress();
            receiverLng = address.getLongitude();
            receiverLat = address.getLatitude();
        }

        BigDecimal payAmount = goodsAmount.add(deliveryFee);

        // 5. 扣减商品库存，增加销量
        product.setStock(product.getStock() - dto.getQuantity());
        product.setSales(product.getSales() + dto.getQuantity());
        productMapper.updateById(product);

        // 6. 订单状态判定
        // 支付方式：1 微信支付(初始为0待支付)；2 货到付款(初始为1待接单)
        int initialStatus;
        int payStatus = 0; // 0 未支付
        if (dto.getPayType() == OrderInfo.PAY_TYPE_COD) {
            initialStatus = OrderInfo.STATUS_PENDING_ACCEPT; // 货到付款直接待接单
        } else {
            initialStatus = OrderInfo.STATUS_PENDING_PAY;    // 微信支付待付款
        }

        OrderInfo order = OrderInfo.builder()
                .orderNo(generateOrderNo())
                .shopId(shop.getId())
                .userId(userId)
                .deliveryType(dto.getDeliveryType())
                .status(initialStatus)
                .payType(dto.getPayType())
                .payStatus(payStatus)
                .receiverName(receiverName)
                .receiverPhone(receiverPhone)
                .receiverAddress(receiverAddress)
                .receiverLng(receiverLng)
                .receiverLat(receiverLat)
                .deliveryDistance(deliveryDistance)
                .deliveryFee(deliveryFee)
                .goodsAmount(goodsAmount)
                .payAmount(payAmount)
                .remark(StringUtils.hasText(dto.getRemark()) ? dto.getRemark() : "")
                .createTime(LocalDateTime.now())
                .build();
        this.save(order);

        // 7. 保存订单明细
        OrderItem item = OrderItem.builder()
                .orderId(order.getId())
                .productId(product.getId())
                .productName(product.getName())
                .productImage(com.freshmeat.util.FileUrlUtil.toRelative(product.getCoverImage()))
                .specInfo(StringUtils.hasText(dto.getSpecInfo()) ? dto.getSpecInfo() : (product.getUnit()))
                .price(product.getPrice())
                .quantity(dto.getQuantity())
                .subtotal(goodsAmount)
                .build();
        orderItemMapper.insert(item);

        List<OrderItem> items = new ArrayList<>();
        items.add(item);
        return convertToVO(order, items);
    }

    @Override
    public PageResult<OrderVO> pageCustomerOrders(OrderQueryDTO queryDTO) {
        String userId = SecurityContextUtil.getUserId();
        if (userId == null) {
            throw new BusinessException(401, "请先登录");
        }

        Page<OrderInfo> page = new Page<>(queryDTO.getPageNum(), queryDTO.getPageSize());
        LambdaQueryWrapper<OrderInfo> qw = new LambdaQueryWrapper<OrderInfo>()
                .eq(OrderInfo::getUserId, userId);

        if (queryDTO.getStatus() != null) {
            qw.eq(OrderInfo::getStatus, queryDTO.getStatus());
        }
        if (queryDTO.getDeliveryType() != null) {
            qw.eq(OrderInfo::getDeliveryType, queryDTO.getDeliveryType());
        }
        qw.orderByDesc(OrderInfo::getCreateTime);

        Page<OrderInfo> resultPage = this.page(page, qw);
        List<OrderVO> voList = resultPage.getRecords().stream()
                .map(this::loadOrderVOWithItems)
                .collect(Collectors.toList());

        return PageResult.of(resultPage.getTotal(), voList, resultPage.getCurrent(), resultPage.getSize());
    }

    @Override
    public PageResult<OrderVO> pageAdminOrders(OrderQueryDTO queryDTO) {
        String currentUserId = SecurityContextUtil.getUserId();
        Page<OrderInfo> page = new Page<>(queryDTO.getPageNum(), queryDTO.getPageSize());
        LambdaQueryWrapper<OrderInfo> qw = new LambdaQueryWrapper<OrderInfo>();

        if (StringUtils.hasText(queryDTO.getShopId())) {
            if (!shopInfoService.hasShopPermission(currentUserId, queryDTO.getShopId())) {
                throw new BusinessException(403, "无权查看该店铺订单");
            }
            qw.eq(OrderInfo::getShopId, queryDTO.getShopId());
        } else {
            List<String> adminShopIds = shopInfoService.getAdminShopIds(currentUserId);
            if (org.springframework.util.CollectionUtils.isEmpty(adminShopIds)) {
                return PageResult.of(0L, Collections.emptyList(), queryDTO.getPageNum(), queryDTO.getPageSize());
            }
            qw.in(OrderInfo::getShopId, adminShopIds);
        }

        if (queryDTO.getStatus() != null) {
            qw.eq(OrderInfo::getStatus, queryDTO.getStatus());
        }
        if (queryDTO.getDeliveryType() != null) {
            qw.eq(OrderInfo::getDeliveryType, queryDTO.getDeliveryType());
        }
        qw.orderByDesc(OrderInfo::getCreateTime);

        Page<OrderInfo> resultPage = this.page(page, qw);
        List<OrderVO> voList = resultPage.getRecords().stream()
                .map(this::loadOrderVOWithItems)
                .collect(Collectors.toList());

        return PageResult.of(resultPage.getTotal(), voList, resultPage.getCurrent(), resultPage.getSize());
    }

    @Override
    public OrderVO getOrderDetail(String orderId) {
        OrderInfo order = this.getById(orderId);
        if (order == null) {
            throw new BusinessException("订单不存在");
        }
        // 顾客只能查看自己的订单，管理员需拥有该店铺权限
        String currentUserId = SecurityContextUtil.getUserId();
        if (!SecurityContextUtil.isAdmin()) {
            if (!order.getUserId().equals(currentUserId)) {
                throw new BusinessException(403, "无权查看该订单");
            }
        } else {
            if (!shopInfoService.hasShopPermission(currentUserId, order.getShopId())) {
                throw new BusinessException(403, "无权查看该分店订单");
            }
        }
        return loadOrderVOWithItems(order);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean cancelOrder(String orderId, OrderStatusUpdateDTO dto) {
        OrderInfo order = this.getById(orderId);
        if (order == null) {
            throw new BusinessException("订单不存在");
        }
        String currentUserId = SecurityContextUtil.getUserId();
        if (!SecurityContextUtil.isAdmin() && !order.getUserId().equals(currentUserId)) {
            throw new BusinessException(403, "无权操作该订单");
        }

        // 仅在待支付(0) 或 待接单(1) 时允许取消
        if (order.getStatus() != OrderInfo.STATUS_PENDING_PAY && order.getStatus() != OrderInfo.STATUS_PENDING_ACCEPT) {
            throw new BusinessException("当前订单状态不可取消");
        }

        // 回退商品库存
        restoreOrderStock(order.getId());

        // 如果已支付，执行退款
        if (order.getPayStatus() == 1) {
            paymentService.processRefund(order, "顾客主动取消订单");
        }

        order.setStatus(OrderInfo.STATUS_CANCELLED);
        order.setCancelReason(dto != null && StringUtils.hasText(dto.getReason()) ? dto.getReason() : "顾客取消订单");
        order.setCancelTime(LocalDateTime.now());
        return this.updateById(order);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean acceptOrder(String orderId) {
        OrderInfo order = this.getById(orderId);
        if (order == null) {
            throw new BusinessException("订单不存在");
        }
        String currentUserId = SecurityContextUtil.getUserId();
        if (!shopInfoService.hasShopPermission(currentUserId, order.getShopId())) {
            throw new BusinessException(403, "无权处理该分店订单");
        }
        if (order.getStatus() != OrderInfo.STATUS_PENDING_ACCEPT) {
            throw new BusinessException("只能接单待接单状态的订单");
        }

        // 状态流转：1 -> 2 (已接单：待配送/待自取)
        order.setStatus(OrderInfo.STATUS_ACCEPTED);
        order.setAcceptTime(LocalDateTime.now());
        return this.updateById(order);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean rejectOrder(String orderId, OrderStatusUpdateDTO dto) {
        OrderInfo order = this.getById(orderId);
        if (order == null) {
            throw new BusinessException("订单不存在");
        }
        String currentUserId = SecurityContextUtil.getUserId();
        if (!shopInfoService.hasShopPermission(currentUserId, order.getShopId())) {
            throw new BusinessException(403, "无权处理该分店订单");
        }
        if (order.getStatus() != OrderInfo.STATUS_PENDING_ACCEPT) {
            throw new BusinessException("只能拒单待接单状态的订单");
        }

        // 回退库存
        restoreOrderStock(order.getId());

        // 如果已付款，触发退款
        if (order.getPayStatus() == 1) {
            paymentService.processRefund(order, "商家拒单");
        }

        order.setStatus(OrderInfo.STATUS_REJECTED);
        order.setRejectReason(dto != null && StringUtils.hasText(dto.getReason()) ? dto.getReason() : "商家忙碌，暂时无法接单");
        order.setCancelTime(LocalDateTime.now());
        return this.updateById(order);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deliverOrder(String orderId) {
        OrderInfo order = this.getById(orderId);
        if (order == null) {
            throw new BusinessException("订单不存在");
        }
        String currentUserId = SecurityContextUtil.getUserId();
        if (!shopInfoService.hasShopPermission(currentUserId, order.getShopId())) {
            throw new BusinessException(403, "无权处理该分店订单");
        }
        if (order.getStatus() != OrderInfo.STATUS_ACCEPTED) {
            throw new BusinessException("订单当前不是已接单待配货状态");
        }

        if (order.getDeliveryType() == OrderInfo.DELIVERY_MERCHANT) {
            // 商家配送：状态流转为 3 配送中
            order.setStatus(OrderInfo.STATUS_DELIVERING);
        } else {
            // 自取订单：保持状态或通知备好
            order.setStatus(OrderInfo.STATUS_ACCEPTED);
        }
        order.setDeliverTime(LocalDateTime.now());
        return this.updateById(order);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean finishOrder(String orderId) {
        OrderInfo order = this.getById(orderId);
        if (order == null) {
            throw new BusinessException("订单不存在");
        }
        String currentUserId = SecurityContextUtil.getUserId();
        // 顾客在配送中状态确认收货；掌柜在自提或配送核销
        if (SecurityContextUtil.isAdmin()) {
            if (!shopInfoService.hasShopPermission(currentUserId, order.getShopId())) {
                throw new BusinessException(403, "无权处理该分店订单");
            }
        } else {
            if (!order.getUserId().equals(currentUserId)) {
                throw new BusinessException(403, "无权操作该订单");
            }
        }

        // 可确认完成的状态：配送中(3)，或者自取订单在待自提(2)
        if (order.getStatus() != OrderInfo.STATUS_DELIVERING && order.getStatus() != OrderInfo.STATUS_ACCEPTED) {
            throw new BusinessException("当前订单状态不可确认完成");
        }

        order.setStatus(OrderInfo.STATUS_COMPLETED);
        order.setFinishTime(LocalDateTime.now());

        // 如果是货到付款，完成时将支付状态标记为已收款
        if (order.getPayType() == OrderInfo.PAY_TYPE_COD) {
            order.setPayStatus(1);
            order.setPayTime(LocalDateTime.now());
        }

        return this.updateById(order);
    }

    private void restoreOrderStock(String orderId) {
        List<OrderItem> items = orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderId, orderId));
        for (OrderItem item : items) {
            Product product = productMapper.selectById(item.getProductId());
            if (product != null) {
                product.setStock(product.getStock() + item.getQuantity());
                product.setSales(Math.max(0, product.getSales() - item.getQuantity()));
                productMapper.updateById(product);
            }
        }
    }

    private OrderVO loadOrderVOWithItems(OrderInfo order) {
        List<OrderItem> items = orderItemMapper.selectList(
                new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderId, order.getId()));
        return convertToVO(order, items);
    }

    private OrderVO convertToVO(OrderInfo order, List<OrderItem> items) {
        OrderVO vo = new OrderVO();
        BeanUtils.copyProperties(order, vo);
        vo.setItems(items);
        vo.setShopId(order.getShopId());
        if (StringUtils.hasText(order.getShopId())) {
            ShopInfo shop = shopInfoService.getById(order.getShopId());
            vo.setShopName(shop != null ? shop.getName() : "维琼鲜肉铺");
        } else {
            vo.setShopName("维琼鲜肉铺");
        }

        // 配送方式描述
        if (order.getDeliveryType() == OrderInfo.DELIVERY_PICKUP) {
            vo.setDeliveryTypeDesc("自取(到店)");
        } else if (order.getDeliveryType() == OrderInfo.DELIVERY_MERCHANT) {
            vo.setDeliveryTypeDesc("商家配送");
        } else {
            vo.setDeliveryTypeDesc("外卖");
        }

        // 支付方式描述
        vo.setPayTypeDesc(order.getPayType() == OrderInfo.PAY_TYPE_WECHAT ? "微信支付" : "货到付款");

        // 支付状态描述
        if (order.getPayStatus() == 1) {
            vo.setPayStatusDesc("已支付");
        } else if (order.getPayStatus() == 2) {
            vo.setPayStatusDesc("已退款");
        } else {
            vo.setPayStatusDesc("未支付");
        }

        // 状态流转文案与操作按钮权限控制
        int status = order.getStatus();
        switch (status) {
            case OrderInfo.STATUS_PENDING_PAY:
                vo.setStatusDesc("待支付");
                vo.setCanPay(true);
                vo.setCanCancel(true);
                break;
            case OrderInfo.STATUS_PENDING_ACCEPT:
                vo.setStatusDesc("待接单");
                vo.setCanCancel(true);
                vo.setCanAccept(true);
                vo.setCanReject(true);
                break;
            case OrderInfo.STATUS_ACCEPTED:
                vo.setStatusDesc(order.getDeliveryType() == OrderInfo.DELIVERY_PICKUP ? "待自提" : "待发货");
                vo.setCanDeliver(true);
                if (order.getDeliveryType() == OrderInfo.DELIVERY_PICKUP) {
                    vo.setCanFinish(true); // 自提可直接核销
                }
                break;
            case OrderInfo.STATUS_DELIVERING:
                vo.setStatusDesc("配送中");
                vo.setCanConfirm(true); // 顾客确认收货
                vo.setCanFinish(true);  // 商家确认送达
                break;
            case OrderInfo.STATUS_COMPLETED:
                vo.setStatusDesc("已完成");
                break;
            case OrderInfo.STATUS_CANCELLED:
                vo.setStatusDesc("已取消");
                break;
            case OrderInfo.STATUS_REJECTED:
                vo.setStatusDesc("已拒单");
                break;
            default:
                vo.setStatusDesc("未知");
        }

        return vo;
    }
}
