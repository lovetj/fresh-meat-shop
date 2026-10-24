package com.freshmeat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.freshmeat.common.PageResult;
import com.freshmeat.dto.DistanceCheckVO;
import com.freshmeat.dto.ExpressCheckVO;
import com.freshmeat.dto.OrderCreateDTO;
import com.freshmeat.dto.OrderExpressDTO;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private final com.freshmeat.service.ShopDeliveryRuleService deliveryRuleService;

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
            throw new BusinessException("外卖配送通道暂未开通，仅支持「自取」「商家配送」或「快递配送」");
        }
        if (dto.getDeliveryType() != OrderInfo.DELIVERY_PICKUP
                && dto.getDeliveryType() != OrderInfo.DELIVERY_MERCHANT
                && dto.getDeliveryType() != OrderInfo.DELIVERY_EXPRESS) {
            throw new BusinessException("不支持的配送方式");
        }

        // 2. 统一转换为订单项明细列表
        List<OrderCreateDTO.OrderItemDTO> rawItems = dto.getItems();
        if (rawItems == null || rawItems.isEmpty()) {
            if (!StringUtils.hasText(dto.getProductId()) || dto.getQuantity() == null || dto.getQuantity() < 1) {
                throw new BusinessException("请选择要购买的商品");
            }
            OrderCreateDTO.OrderItemDTO single = new OrderCreateDTO.OrderItemDTO();
            single.setProductId(dto.getProductId());
            single.setQuantity(dto.getQuantity());
            single.setSpecInfo(dto.getSpecInfo());
            rawItems = Collections.singletonList(single);
        }

        // 校验购买项基本属性，并按 productId 累加总需求数量防止同一商品在不同规格下总库存超卖
        Map<String, Integer> productTotalQuantityMap = new HashMap<>();
        for (OrderCreateDTO.OrderItemDTO itemDto : rawItems) {
            if (!StringUtils.hasText(itemDto.getProductId())) {
                throw new BusinessException("购买商品ID不能为空");
            }
            if (itemDto.getQuantity() == null || itemDto.getQuantity() < 1) {
                throw new BusinessException("商品购买数量至少为1");
            }
            productTotalQuantityMap.merge(itemDto.getProductId(), itemDto.getQuantity(), Integer::sum);
        }

        // 查询并校验所有商品，并确认店铺
        Map<String, Product> productMap = new HashMap<>();
        String determinedShopId = dto.getShopId();

        for (Map.Entry<String, Integer> entry : productTotalQuantityMap.entrySet()) {
            String pId = entry.getKey();
            int requiredQty = entry.getValue();
            Product product = productMapper.selectById(pId);
            if (product == null) {
                throw new BusinessException("商品不存在");
            }
            if (product.getIsOnSale() == null || product.getIsOnSale() != 1) {
                throw new BusinessException("商品「" + product.getName() + "」已下架");
            }
            if (product.getStock() < requiredQty) {
                throw new BusinessException("商品「" + product.getName() + "」库存不足，当前剩余: " + product.getStock());
            }
            // 校验店铺归属
            if (StringUtils.hasText(product.getShopId())) {
                if (!StringUtils.hasText(determinedShopId)) {
                    determinedShopId = product.getShopId();
                } else if (!determinedShopId.equals(product.getShopId())) {
                    throw new BusinessException("所选商品属于不同门店，请分别下单");
                }
            }
            productMap.put(pId, product);
        }

        if (!StringUtils.hasText(determinedShopId)) {
            determinedShopId = "s_shop_000000000000000000000000001";
        }

        ShopInfo shop = shopInfoService.getShopInfo(determinedShopId);
        if (shop == null) {
            throw new BusinessException("下单店铺不存在");
        }
        if (shop.getStatus() != null && shop.getStatus() == 0) {
            throw new BusinessException("「" + shop.getName() + "」当前正在休息中，暂不接单");
        }

        // 3. 计算商品总额与明细小计
        BigDecimal goodsAmount = BigDecimal.ZERO;
        List<OrderItem> orderItemsToSave = new ArrayList<>();

        for (OrderCreateDTO.OrderItemDTO itemDto : rawItems) {
            Product product = productMap.get(itemDto.getProductId());
            BigDecimal itemSubtotal = product.getPrice().multiply(new BigDecimal(itemDto.getQuantity()));
            goodsAmount = goodsAmount.add(itemSubtotal);

            OrderItem item = OrderItem.builder()
                    .productId(product.getId())
                    .productName(product.getName())
                    .productImage(com.freshmeat.util.FileUrlUtil.toRelative(product.getCoverImage()))
                    .specInfo(StringUtils.hasText(itemDto.getSpecInfo()) ? itemDto.getSpecInfo() : product.getUnit())
                    .price(product.getPrice())
                    .quantity(itemDto.getQuantity())
                    .subtotal(itemSubtotal)
                    .createTime(LocalDateTime.now())
                    .build();
            orderItemsToSave.add(item);
        }

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
            // 商家配送 / 快递配送：均需选择收货地址
            if (dto.getAddressId() == null) {
                throw new BusinessException("请选择收货地址");
            }
            UserAddress address = userAddressMapper.selectById(dto.getAddressId());
            if (address == null || !address.getUserId().equals(userId)) {
                throw new BusinessException("收货地址不存在");
            }

            if (dto.getDeliveryType() == OrderInfo.DELIVERY_EXPRESS) {
                // 快递配送：按店铺快递配置与收货地址计算运费（特殊地区可加价或停发），不受同城距离与起送价限制
                ExpressCheckVO expressCheck = shopInfoService.calculateExpress(shop, address);
                if (!Boolean.TRUE.equals(expressCheck.getCanDeliver())) {
                    throw new BusinessException(expressCheck.getMessage());
                }
                deliveryFee = expressCheck.getFee() != null ? expressCheck.getFee() : BigDecimal.ZERO;
                deliveryDistance = BigDecimal.ZERO;
            } else {
                // 商家配送：校验地址定位并进行同城测距与阶梯运费计算
                if (address.getLongitude() == null || address.getLatitude() == null) {
                    throw new BusinessException("该地址缺少经纬度定位，请重新编辑完善");
                }

                // 核心业务校验：根据经纬度进行同城测距与多阶梯配送规则计算
                double actualDist = DistanceUtil.calculateDistance(
                        shop.getLongitude().doubleValue(), shop.getLatitude().doubleValue(),
                        address.getLongitude().doubleValue(), address.getLatitude().doubleValue()
                );

                DistanceCheckVO deliveryCheck = deliveryRuleService.calculateDelivery(shop, actualDist);
                if (!Boolean.TRUE.equals(deliveryCheck.getCanDeliver())) {
                    throw new BusinessException(deliveryCheck.getMessage());
                }

                // 动态起送金额校验
                BigDecimal requiredMinOrder = deliveryCheck.getMinOrderAmount() != null
                        ? deliveryCheck.getMinOrderAmount() : BigDecimal.ZERO;
                if (goodsAmount.compareTo(requiredMinOrder) < 0) {
                    BigDecimal diff = requiredMinOrder.subtract(goodsAmount);
                    throw new BusinessException(String.format("当前配送距离约 %.2f 公里，未达到该距离起送金额(¥%.2f)，当前商品金额为¥%.2f，还差¥%.2f 起送",
                            actualDist, requiredMinOrder.doubleValue(), goodsAmount.doubleValue(), diff.doubleValue()));
                }

                deliveryDistance = BigDecimal.valueOf(deliveryCheck.getDistance());
                deliveryFee = deliveryCheck.getDeliveryFee() != null ? deliveryCheck.getDeliveryFee() : BigDecimal.ZERO;
            }

            receiverName = address.getContactName();
            receiverPhone = address.getContactPhone();
            receiverAddress = address.getFullAddress();
            receiverLng = address.getLongitude();
            receiverLat = address.getLatitude();
        }

        BigDecimal payAmount = goodsAmount.add(deliveryFee);

        // 5. 扣减商品库存，增加销量
        for (Map.Entry<String, Integer> entry : productTotalQuantityMap.entrySet()) {
            Product product = productMap.get(entry.getKey());
            product.setStock(product.getStock() - entry.getValue());
            product.setSales(product.getSales() + entry.getValue());
            productMapper.updateById(product);
        }

        // 6. 订单状态判定
        // 支付方式：1 微信支付(初始为0待支付)；2 货到付款(初始为1待接单)
        int initialStatus;
        int payStatus = 0; // 0 未支付
        if (dto.getPayType() == OrderInfo.PAY_TYPE_COD) {
            initialStatus = OrderInfo.STATUS_PENDING_ACCEPT; // 货到付款直接待接单
        } else {
            initialStatus = OrderInfo.STATUS_PENDING_PAY;    // 微信支付待付款
        }

        // 自取订单自动生成 6 位自提提货码
        String pickupCode = null;
        if (dto.getDeliveryType() == OrderInfo.DELIVERY_PICKUP) {
            pickupCode = String.format("%06d", (int) ((Math.random() * 9 + 1) * 100000));
        }

        OrderInfo order = OrderInfo.builder()
                .orderNo(generateOrderNo())
                .shopId(shop.getId())
                .userId(userId)
                .deliveryType(dto.getDeliveryType())
                .pickupCode(pickupCode)
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
                .originalPayAmount(payAmount)
                .remark(StringUtils.hasText(dto.getRemark()) ? dto.getRemark() : "")
                .createTime(LocalDateTime.now())
                .build();
        this.save(order);

        // 7. 保存订单明细
        for (OrderItem item : orderItemsToSave) {
            item.setOrderId(order.getId());
            orderItemMapper.insert(item);
        }

        return convertToVO(order, orderItemsToSave);
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

        if (!org.springframework.util.CollectionUtils.isEmpty(queryDTO.getStatus())) {
            qw.in(OrderInfo::getStatus, queryDTO.getStatus());
        }
        if (queryDTO.getDeliveryType() != null) {
            qw.eq(OrderInfo::getDeliveryType, queryDTO.getDeliveryType());
        }
        if (StringUtils.hasText(queryDTO.getKeyword())) {
            String kw = queryDTO.getKeyword().trim();
            qw.and(wrapper -> wrapper.like(OrderInfo::getOrderNo, kw)
                    .or().like(OrderInfo::getReceiverName, kw)
                    .or().like(OrderInfo::getReceiverPhone, kw)
                    .or().like(OrderInfo::getPickupCode, kw));
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

        if (!org.springframework.util.CollectionUtils.isEmpty(queryDTO.getStatus())) {
            qw.in(OrderInfo::getStatus, queryDTO.getStatus());
        }
        if (queryDTO.getDeliveryType() != null) {
            qw.eq(OrderInfo::getDeliveryType, queryDTO.getDeliveryType());
        }
        if (StringUtils.hasText(queryDTO.getKeyword())) {
            String kw = queryDTO.getKeyword().trim();
            qw.and(wrapper -> wrapper.like(OrderInfo::getOrderNo, kw)
                    .or().like(OrderInfo::getReceiverName, kw)
                    .or().like(OrderInfo::getReceiverPhone, kw)
                    .or().like(OrderInfo::getPickupCode, kw));
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
        if (order.getDeliveryType() != null && order.getDeliveryType() == OrderInfo.DELIVERY_EXPRESS) {
            throw new BusinessException("快递配送订单请填写快递公司与快递单号发货");
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
    public boolean deliverByExpress(String orderId, OrderExpressDTO dto) {
        OrderInfo order = this.getById(orderId);
        if (order == null) {
            throw new BusinessException("订单不存在");
        }
        String currentUserId = SecurityContextUtil.getUserId();
        if (!shopInfoService.hasShopPermission(currentUserId, order.getShopId())) {
            throw new BusinessException(403, "无权处理该分店订单");
        }
        if (order.getDeliveryType() == null || order.getDeliveryType() != OrderInfo.DELIVERY_EXPRESS) {
            throw new BusinessException("该订单不是快递配送订单");
        }
        if (order.getStatus() != OrderInfo.STATUS_ACCEPTED) {
            throw new BusinessException("仅已接单待发货的快递订单可填写快递单号");
        }
        if (dto == null || !StringUtils.hasText(dto.getExpressCompany()) || !StringUtils.hasText(dto.getExpressNo())) {
            throw new BusinessException("请填写完整的快递公司与快递单号");
        }

        order.setExpressCompany(dto.getExpressCompany().trim());
        order.setExpressNo(dto.getExpressNo().trim());
        order.setStatus(OrderInfo.STATUS_DELIVERING);
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
            if (shop != null) {
                vo.setShopAddress(shop.getAddress());
                vo.setShopPhone(shop.getPhone());
                vo.setShopLng(shop.getLongitude());
                vo.setShopLat(shop.getLatitude());
            }
        } else {
            vo.setShopName("维琼鲜肉铺");
        }

        // 配送方式描述
        if (order.getDeliveryType() == OrderInfo.DELIVERY_PICKUP) {
            vo.setDeliveryTypeDesc("自取(到店)");
        } else if (order.getDeliveryType() == OrderInfo.DELIVERY_MERCHANT) {
            vo.setDeliveryTypeDesc("商家配送");
        } else if (order.getDeliveryType() == OrderInfo.DELIVERY_EXPRESS) {
            vo.setDeliveryTypeDesc("快递配送");
        } else {
            vo.setDeliveryTypeDesc("外卖");
        }

        // 自提核销码与改价信息
        vo.setPickupCode(order.getPickupCode());
        vo.setOriginalPayAmount(order.getOriginalPayAmount());

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
                vo.setCanChangePrice(true);
                vo.setCanAdminCancel(true);
                break;
            case OrderInfo.STATUS_PENDING_ACCEPT:
                vo.setStatusDesc("待接单");
                vo.setCanCancel(true);
                vo.setCanAccept(true);
                vo.setCanReject(true);
                vo.setCanAdminCancel(true);
                break;
            case OrderInfo.STATUS_ACCEPTED:
                if (order.getDeliveryType() == OrderInfo.DELIVERY_PICKUP) {
                    vo.setStatusDesc("待自提");
                    vo.setCanFinish(true); // 自提可直接核销
                } else if (order.getDeliveryType() == OrderInfo.DELIVERY_EXPRESS) {
                    vo.setStatusDesc("待发货"); // 快递配送：等待掌柜填写快递单号
                } else {
                    vo.setStatusDesc("待发货");
                }
                vo.setCanDeliver(true);
                vo.setCanAdminCancel(true);
                break;
            case OrderInfo.STATUS_DELIVERING:
                vo.setStatusDesc(order.getDeliveryType() == OrderInfo.DELIVERY_EXPRESS ? "快递运输中" : "配送中");
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

    @Override
    public com.freshmeat.dto.OrderStatusCountVO getCustomerStatusCounts() {
        String userId = SecurityContextUtil.getUserId();
        if (userId == null) {
            throw new BusinessException(401, "请先登录");
        }

        long allCount = this.count(new LambdaQueryWrapper<OrderInfo>().eq(OrderInfo::getUserId, userId));
        long pendingPayCount = this.count(new LambdaQueryWrapper<OrderInfo>()
                .eq(OrderInfo::getUserId, userId).eq(OrderInfo::getStatus, OrderInfo.STATUS_PENDING_PAY));
        long pendingAcceptCount = this.count(new LambdaQueryWrapper<OrderInfo>()
                .eq(OrderInfo::getUserId, userId).eq(OrderInfo::getStatus, OrderInfo.STATUS_PENDING_ACCEPT));
        long acceptedCount = this.count(new LambdaQueryWrapper<OrderInfo>()
                .eq(OrderInfo::getUserId, userId).eq(OrderInfo::getStatus, OrderInfo.STATUS_ACCEPTED));
        long deliveringCount = this.count(new LambdaQueryWrapper<OrderInfo>()
                .eq(OrderInfo::getUserId, userId).eq(OrderInfo::getStatus, OrderInfo.STATUS_DELIVERING));
        long completedCount = this.count(new LambdaQueryWrapper<OrderInfo>()
                .eq(OrderInfo::getUserId, userId).eq(OrderInfo::getStatus, OrderInfo.STATUS_COMPLETED));
        long closedCount = this.count(new LambdaQueryWrapper<OrderInfo>()
                .eq(OrderInfo::getUserId, userId)
                .in(OrderInfo::getStatus, OrderInfo.STATUS_CANCELLED, OrderInfo.STATUS_REJECTED));

        return com.freshmeat.dto.OrderStatusCountVO.builder()
                .allCount(allCount)
                .pendingPayCount(pendingPayCount)
                .pendingAcceptCount(pendingAcceptCount)
                .acceptedCount(acceptedCount)
                .deliveringCount(deliveringCount)
                .completedCount(completedCount)
                .closedCount(closedCount)
                .build();
    }

    @Override
    public com.freshmeat.dto.OrderStatusCountVO getAdminStatusCounts(String shopId) {
        String currentUserId = SecurityContextUtil.getUserId();
        List<String> shopIds;
        if (StringUtils.hasText(shopId)) {
            if (!shopInfoService.hasShopPermission(currentUserId, shopId)) {
                throw new BusinessException(403, "无权查看该分店统计");
            }
            shopIds = Collections.singletonList(shopId);
        } else {
            shopIds = shopInfoService.getAdminShopIds(currentUserId);
        }
        if (org.springframework.util.CollectionUtils.isEmpty(shopIds)) {
            return com.freshmeat.dto.OrderStatusCountVO.builder()
                    .allCount(0L).pendingAcceptCount(0L).acceptedCount(0L)
                    .deliveringCount(0L).pendingPayCount(0L).completedCount(0L).closedCount(0L)
                    .build();
        }

        long allCount = this.count(new LambdaQueryWrapper<OrderInfo>().in(OrderInfo::getShopId, shopIds));

        long pendingPayCount = this.count(new LambdaQueryWrapper<OrderInfo>()
                .in(OrderInfo::getShopId, shopIds).eq(OrderInfo::getStatus, OrderInfo.STATUS_PENDING_PAY));

        long pendingAcceptCount = this.count(new LambdaQueryWrapper<OrderInfo>()
                .in(OrderInfo::getShopId, shopIds).eq(OrderInfo::getStatus, OrderInfo.STATUS_PENDING_ACCEPT));

        long acceptedCount = this.count(new LambdaQueryWrapper<OrderInfo>()
                .in(OrderInfo::getShopId, shopIds).eq(OrderInfo::getStatus, OrderInfo.STATUS_ACCEPTED));

        long deliveringCount = this.count(new LambdaQueryWrapper<OrderInfo>()
                .in(OrderInfo::getShopId, shopIds).eq(OrderInfo::getStatus, OrderInfo.STATUS_DELIVERING));

        long completedCount = this.count(new LambdaQueryWrapper<OrderInfo>()
                .in(OrderInfo::getShopId, shopIds).eq(OrderInfo::getStatus, OrderInfo.STATUS_COMPLETED));

        long closedCount = this.count(new LambdaQueryWrapper<OrderInfo>()
                .in(OrderInfo::getShopId, shopIds)
                .in(OrderInfo::getStatus, OrderInfo.STATUS_CANCELLED, OrderInfo.STATUS_REJECTED));

        return com.freshmeat.dto.OrderStatusCountVO.builder()
                .allCount(allCount)
                .pendingPayCount(pendingPayCount)
                .pendingAcceptCount(pendingAcceptCount)
                .acceptedCount(acceptedCount)
                .deliveringCount(deliveringCount)
                .completedCount(completedCount)
                .closedCount(closedCount)
                .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderVO verifyPickupByCode(com.freshmeat.dto.OrderPickupVerifyDTO dto) {
        if (!StringUtils.hasText(dto.getPickupCode())) {
            throw new BusinessException("请输入核销提货码");
        }
        String currentUserId = SecurityContextUtil.getUserId();
        LambdaQueryWrapper<OrderInfo> qw = new LambdaQueryWrapper<OrderInfo>()
                .eq(OrderInfo::getPickupCode, dto.getPickupCode().trim());
        if (StringUtils.hasText(dto.getShopId())) {
            if (!shopInfoService.hasShopPermission(currentUserId, dto.getShopId())) {
                throw new BusinessException(403, "无权处理该分店订单");
            }
            qw.eq(OrderInfo::getShopId, dto.getShopId());
        } else {
            List<String> adminShopIds = shopInfoService.getAdminShopIds(currentUserId);
            if (!org.springframework.util.CollectionUtils.isEmpty(adminShopIds)) {
                qw.in(OrderInfo::getShopId, adminShopIds);
            }
        }
        OrderInfo order = this.getOne(qw, false);
        if (order == null) {
            throw new BusinessException("未找到匹配的自提订单或提货码不存在");
        }
        if (order.getStatus() == OrderInfo.STATUS_COMPLETED) {
            throw new BusinessException("该提货码对应订单已完成核销，请勿重复核销");
        }
        if (order.getStatus() == OrderInfo.STATUS_CANCELLED || order.getStatus() == OrderInfo.STATUS_REJECTED) {
            throw new BusinessException("该提货码对应订单已取消或拒单，无法提货");
        }
        if (order.getStatus() == OrderInfo.STATUS_PENDING_PAY) {
            throw new BusinessException("该订单尚未支付，请先完成支付后再核销");
        }

        // 完成核销：流转到已完成(4)
        order.setStatus(OrderInfo.STATUS_COMPLETED);
        order.setFinishTime(LocalDateTime.now());
        if (order.getPayType() == OrderInfo.PAY_TYPE_COD) {
            order.setPayStatus(1);
            order.setPayTime(LocalDateTime.now());
        }
        this.updateById(order);
        return loadOrderVOWithItems(order);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean changeOrderPrice(String orderId, com.freshmeat.dto.OrderPriceChangeDTO dto) {
        OrderInfo order = this.getById(orderId);
        if (order == null) {
            throw new BusinessException("订单不存在");
        }
        String currentUserId = SecurityContextUtil.getUserId();
        if (!shopInfoService.hasShopPermission(currentUserId, order.getShopId())) {
            throw new BusinessException(403, "无权处理该分店订单");
        }
        if (order.getStatus() != OrderInfo.STATUS_PENDING_PAY || order.getPayStatus() != 0) {
            throw new BusinessException("仅未支付订单允许掌柜改价");
        }
        if (dto.getNewPayAmount() == null || dto.getNewPayAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("调整后的金额必须大于0");
        }
        if (order.getOriginalPayAmount() == null) {
            order.setOriginalPayAmount(order.getPayAmount());
        }
        BigDecimal oldAmount = order.getPayAmount();
        order.setPayAmount(dto.getNewPayAmount());
        String reasonStr = StringUtils.hasText(dto.getReason()) ? "，原因: " + dto.getReason() : "";
        log.info("[掌柜改价] 订单: {}，¥{} -> ¥{}{}", orderId, oldAmount, dto.getNewPayAmount(), reasonStr);
        return this.updateById(order);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean adminCancelOrder(String orderId, OrderStatusUpdateDTO dto) {
        OrderInfo order = this.getById(orderId);
        if (order == null) {
            throw new BusinessException("订单不存在");
        }
        String currentUserId = SecurityContextUtil.getUserId();
        if (!shopInfoService.hasShopPermission(currentUserId, order.getShopId())) {
            throw new BusinessException(403, "无权处理该分店订单");
        }
        if (order.getStatus() == OrderInfo.STATUS_COMPLETED || order.getStatus() == OrderInfo.STATUS_CANCELLED || order.getStatus() == OrderInfo.STATUS_REJECTED) {
            throw new BusinessException("当前订单已完结，无法取消");
        }

        // 回退商品库存
        restoreOrderStock(order.getId());

        // 如果已支付，执行退款
        if (order.getPayStatus() == 1) {
            paymentService.processRefund(order, "掌柜协商取消退款");
        }

        order.setStatus(OrderInfo.STATUS_CANCELLED);
        String reason = (dto != null && StringUtils.hasText(dto.getReason())) ? dto.getReason() : "掌柜协商取消";
        order.setCancelReason("[掌柜取消] " + reason);
        order.setCancelTime(LocalDateTime.now());
        return this.updateById(order);
    }
}
