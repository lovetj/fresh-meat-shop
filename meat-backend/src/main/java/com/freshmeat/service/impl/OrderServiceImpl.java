package com.freshmeat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freshmeat.common.PageResult;
import com.freshmeat.dto.DistanceCheckVO;
import com.freshmeat.dto.ExpressCheckVO;
import com.freshmeat.dto.OrderCreateDTO;
import com.freshmeat.dto.OrderExpressDTO;
import com.freshmeat.dto.OrderPrecheckDTO;
import com.freshmeat.dto.OrderPrecheckVO;
import com.freshmeat.dto.OrderQueryDTO;
import com.freshmeat.dto.OrderStatusUpdateDTO;
import com.freshmeat.dto.OrderVO;
import com.freshmeat.entity.*;
import com.freshmeat.enums.ShopStatusEnum;
import com.freshmeat.exception.BusinessException;
import com.freshmeat.mapper.CategoryMapper;
import com.freshmeat.mapper.OrderItemMapper;
import com.freshmeat.mapper.OrderInfoMapper;
import com.freshmeat.mapper.ProductMapper;
import com.freshmeat.mapper.UserAddressMapper;
import com.freshmeat.mapper.UserMapper;
import com.freshmeat.service.OrderService;
import com.freshmeat.service.PaymentService;
import com.freshmeat.service.ShopInfoService;
import com.freshmeat.util.DeliveryTimeUtil;
import com.freshmeat.util.DistanceUtil;
import com.freshmeat.util.SecurityContextUtil;
import com.freshmeat.util.ShopStatusUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl extends ServiceImpl<OrderInfoMapper, OrderInfo> implements OrderService {

    private final OrderItemMapper orderItemMapper;
    private final ProductMapper productMapper;
    private final CategoryMapper categoryMapper;
    private final UserAddressMapper userAddressMapper;
    private final UserMapper userMapper;
    private final ShopInfoService shopInfoService;
    private final PaymentService paymentService;
    private final com.freshmeat.service.ShopDeliveryRuleService deliveryRuleService;

    /** 规格JSON解析（商品 specs_json 为 [{"name":"整块","options":["原样整块"]}] 结构） */
    private static final ObjectMapper SPECS_PARSER = new ObjectMapper();

    private String generateOrderNo() {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        int rand = (int) ((Math.random() * 9 + 1) * 1000);
        return "O" + ts + rand;
    }

    /**
     * 当前配送方式下所选支付方式是否可用
     *
     * @return null 表示可用；否则返回应提示给顾客的文案
     */
    private String payTypeUnavailableMessage(ShopInfo shop, Integer deliveryType, Integer payType) {
        if (shop == null || payType == null) {
            return "支付方式不可用，请刷新后重试";
        }
        if (payType == OrderInfo.PAY_TYPE_WECHAT && shop.wxpayEnabledOf(deliveryType) != 1) {
            return "当前配送方式暂未开通微信线上支付，请选择货到付款";
        }
        if (payType == OrderInfo.PAY_TYPE_COD && shop.codEnabledOf(deliveryType) != 1) {
            return "当前配送方式暂未开通货到付款，请选择微信线上支付";
        }
        return null;
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
        // 歇业中相当于关门，一律禁止下单；休息中仅影响展示（可下单，掌柜营业后处理）
        ShopStatusUtil.assertAllowOrder(shop);

        // 2.1 支付方式校验：必须选择，且该配送方式已在掌柜端开启对应支付方式
        Integer payType = dto.getPayType();
        if (payType == null) {
            throw new BusinessException("请选择支付方式");
        }
        if (payType != OrderInfo.PAY_TYPE_WECHAT && payType != OrderInfo.PAY_TYPE_COD) {
            throw new BusinessException("不支持的支付方式");
        }
        String payTypeError = payTypeUnavailableMessage(shop, dto.getDeliveryType(), payType);
        if (payTypeError != null) {
            throw new BusinessException(payTypeError);
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

        // 3.1 期望送达/到店自取时间校验：必须晚于「当前时间 + 店铺配置的最小备货/配送时长」
        LocalDateTime expectedTime;
        try {
            expectedTime = DeliveryTimeUtil.parseAndValidate(dto.getExpectedTime(), shop, dto.getDeliveryType());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(e.getMessage());
        }

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
                .expectedTime(expectedTime)
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

    // ==================================================================================
    // 下单前统一校验（只读，不写库）：商品/分类/门店/配送范围/价格/规格/期望时间/运费
    // ==================================================================================

    @Override
    public OrderPrecheckVO precheck(OrderPrecheckDTO dto) {
        String userId = SecurityContextUtil.getUserId();
        if (userId == null) {
            throw new BusinessException(401, "请先登录");
        }
        if (dto == null) {
            throw new BusinessException("校验参数不能为空");
        }

        List<OrderPrecheckVO.Issue> issues = new ArrayList<>();
        List<OrderPrecheckVO.ItemResult> itemResults = new ArrayList<>();
        boolean priceChanged = false;
        boolean specChanged = false;
        boolean deliveryFeeChanged = false;

        // ---------- 1. 配送方式校验 ----------
        Integer deliveryType = dto.getDeliveryType();
        if (deliveryType == null) {
            addIssue(issues, OrderPrecheckVO.CODE_DELIVERY_TYPE_INVALID, "请选择配送方式", null);
        } else if (deliveryType == OrderInfo.DELIVERY_TAKEOUT) {
            addIssue(issues, OrderPrecheckVO.CODE_DELIVERY_CHANNEL_CLOSED,
                    "外卖配送通道暂未开通，仅支持「自取」「商家配送」或「快递配送」", null);
        } else if (deliveryType != OrderInfo.DELIVERY_PICKUP
                && deliveryType != OrderInfo.DELIVERY_MERCHANT
                && deliveryType != OrderInfo.DELIVERY_EXPRESS) {
            addIssue(issues, OrderPrecheckVO.CODE_DELIVERY_TYPE_INVALID, "不支持的配送方式", null);
        }

        // ---------- 2. 商品明细归一（购物车批量 / 单品直购） ----------
        List<OrderPrecheckDTO.PrecheckItemDTO> rawItems = new ArrayList<>();
        if (dto.getItems() != null && !dto.getItems().isEmpty()) {
            rawItems.addAll(dto.getItems());
        } else if (StringUtils.hasText(dto.getProductId())) {
            OrderPrecheckDTO.PrecheckItemDTO single = new OrderPrecheckDTO.PrecheckItemDTO();
            single.setProductId(dto.getProductId());
            single.setQuantity(dto.getQuantity());
            single.setSpecInfo(dto.getSpecInfo());
            single.setPrice(dto.getPrice());
            single.setSubtotal(dto.getSubtotal());
            rawItems.add(single);
        }
        if (rawItems.isEmpty()) {
            addIssue(issues, OrderPrecheckVO.CODE_NO_ITEM, "请选择要购买的商品", null);
            return failFast(issues);
        }

        // 按 productId 累加总需求数量，同一商品不同规格合并比较库存（与下单逻辑保持一致）
        Map<String, Integer> productTotalQuantityMap = new LinkedHashMap<>();
        for (OrderPrecheckDTO.PrecheckItemDTO itemDto : rawItems) {
            if (!StringUtils.hasText(itemDto.getProductId())) {
                addIssue(issues, OrderPrecheckVO.CODE_PRODUCT_DELETED, "购买商品ID不能为空", null);
                continue;
            }
            if (itemDto.getQuantity() == null || itemDto.getQuantity() < 1) {
                addIssue(issues, OrderPrecheckVO.CODE_NO_ITEM, "商品购买数量至少为1", itemDto.getProductId());
                continue;
            }
            productTotalQuantityMap.merge(itemDto.getProductId(), itemDto.getQuantity(), Integer::sum);
        }

        // ---------- 3. 商品 / 分类 / 门店归属校验 ----------
        Map<String, Product> productMap = new HashMap<>();
        Map<String, Category> categoryMap = new HashMap<>();
        String determinedShopId = dto.getShopId();

        for (Map.Entry<String, Integer> entry : productTotalQuantityMap.entrySet()) {
            String productId = entry.getKey();
            int requiredQty = entry.getValue();
            Product product = productMapper.selectById(productId);
            if (product == null) {
                addIssue(issues, OrderPrecheckVO.CODE_PRODUCT_DELETED,
                        "购物车中有商品已被商家删除，请返回购物车刷新后重新下单", productId);
                continue;
            }
            productMap.put(productId, product);

            // 商品是否已下架
            if (product.getIsOnSale() == null || product.getIsOnSale() != 1) {
                addIssue(issues, OrderPrecheckVO.CODE_PRODUCT_OFF_SALE,
                        "商品「" + product.getName() + "」已下架，请返回购物车移除后重新下单", productId);
            }

            // 库存是否充足
            int stock = product.getStock() == null ? 0 : product.getStock();
            if (stock < requiredQty) {
                addIssue(issues, OrderPrecheckVO.CODE_STOCK_NOT_ENOUGH,
                        "商品「" + product.getName() + "」库存不足，当前仅剩 " + stock + " 份", productId);
            }

            // 分类是否已删除 / 停用
            checkCategory(product, categoryMap, issues);

            // 门店归属（跨店商品无法一起下单）
            if (StringUtils.hasText(product.getShopId())) {
                if (!StringUtils.hasText(determinedShopId)) {
                    determinedShopId = product.getShopId();
                } else if (!determinedShopId.equals(product.getShopId())) {
                    addIssue(issues, OrderPrecheckVO.CODE_WRONG_SHOP, "所选商品属于不同门店，请分别下单", productId);
                }
            }
        }

        // ---------- 4. 门店是否存在 / 是否歇业 ----------
        if (!StringUtils.hasText(determinedShopId)) {
            determinedShopId = "s_shop_000000000000000000000000001";
        }
        ShopInfo shop = shopInfoService.getById(determinedShopId);
        if (shop == null) {
            if (StringUtils.hasText(dto.getShopId())) {
                addIssue(issues, OrderPrecheckVO.CODE_SHOP_NOT_FOUND, "下单门店不存在或已被删除，请返回选择其他门店", null);
            } else {
                // 与下单接口保持一致的兜底：未指定门店时取默认门店
                shop = shopInfoService.getShopInfo(null);
            }
        }
        if (shop != null) {
            // 补齐配送阶梯规则、快递特殊地区、营业状态等扩展配置
            shop = shopInfoService.getShopInfo(shop.getId());
            ShopStatusEnum displayStatus = ShopStatusUtil.resolve(shop);
            if (displayStatus == ShopStatusEnum.CLOSED) {
                addIssue(issues, OrderPrecheckVO.CODE_SHOP_CLOSED, ShopStatusUtil.orderBlockedMessage(shop), null);
            }
        }

        // ---------- 4.1 支付方式校验（与下单接口保持一致，防止绕过前端） ----------
        boolean deliveryTypeValid = deliveryType != null
                && (deliveryType == OrderInfo.DELIVERY_PICKUP
                || deliveryType == OrderInfo.DELIVERY_MERCHANT
                || deliveryType == OrderInfo.DELIVERY_EXPRESS);
        if (shop != null && deliveryTypeValid) {
            Integer payType = dto.getPayType();
            if (payType == null) {
                addIssue(issues, OrderPrecheckVO.CODE_PAY_TYPE_INVALID, "请选择支付方式", null);
            } else if (payType != OrderInfo.PAY_TYPE_WECHAT && payType != OrderInfo.PAY_TYPE_COD) {
                addIssue(issues, OrderPrecheckVO.CODE_PAY_TYPE_INVALID, "不支持的支付方式", null);
            } else {
                String payTypeError = payTypeUnavailableMessage(shop, deliveryType, payType);
                if (payTypeError != null) {
                    addIssue(issues, OrderPrecheckVO.CODE_PAY_TYPE_UNAVAILABLE, payTypeError, null);
                }
            }
        }

        // ---------- 5. 商品金额 / 价格 / 规格一致性校验 ----------
        BigDecimal goodsAmount = BigDecimal.ZERO;
        for (OrderPrecheckDTO.PrecheckItemDTO itemDto : rawItems) {
            Product product = productMap.get(itemDto.getProductId());
            OrderPrecheckVO.ItemResult.ItemResultBuilder resultBuilder = OrderPrecheckVO.ItemResult.builder()
                    .productId(itemDto.getProductId())
                    .quantity(itemDto.getQuantity())
                    .specInfo(itemDto.getSpecInfo())
                    .clientPrice(itemDto.getPrice())
                    .purchasable(Boolean.FALSE)
                    .valid(Boolean.FALSE);

            if (product == null) {
                itemResults.add(resultBuilder
                        .invalidReason("商品已被删除")
                        .build());
                continue;
            }

            BigDecimal dbPrice = product.getPrice() == null ? BigDecimal.ZERO : product.getPrice();
            int qty = itemDto.getQuantity() == null ? 0 : itemDto.getQuantity();
            BigDecimal subtotal = dbPrice.multiply(new BigDecimal(qty));
            goodsAmount = goodsAmount.add(subtotal);

            // 价格一致性：以数据库价格为准，页面快照仅用于比对（不信任前端价格）
            boolean priceMatched = itemDto.getPrice() == null || sameAmount(itemDto.getPrice(), dbPrice);
            if (!priceMatched) {
                priceChanged = true;
                addIssue(issues, OrderPrecheckVO.CODE_PRICE_CHANGED,
                        String.format("商品「%s」价格已更新为 ¥%.2f（原 ¥%.2f），请刷新购物车确认后重新下单",
                                product.getName(), dbPrice.doubleValue(), itemDto.getPrice().doubleValue()),
                        product.getId());
            }

            // 规格一致性：校验所选加工方式/规格是否仍在商品可选规格内
            boolean specMatched = isSpecMatched(product, itemDto.getSpecInfo());
            if (!specMatched) {
                specChanged = true;
                addIssue(issues, OrderPrecheckVO.CODE_SPEC_CHANGED,
                        String.format("商品「%s」的可选规格已变更，所选「%s」已不可用，请重新选择",
                                product.getName(), itemDto.getSpecInfo()),
                        product.getId());
            }

            boolean onSale = product.getIsOnSale() != null && product.getIsOnSale() == 1;
            Category category = categoryMap.get(normalize(product.getCategoryId()));
            boolean categoryValid = category != null
                    && (category.getStatus() == null || category.getStatus() == 1);
            boolean purchasable = onSale && categoryValid;
            int stock = product.getStock() == null ? 0 : product.getStock();
            boolean stockEnough = stock >= totalQuantityOf(product.getId(), rawItems);
            boolean valid = purchasable && stockEnough && priceMatched && specMatched;

            String invalidReason = null;
            if (!onSale) {
                invalidReason = "商品已下架";
            } else if (!categoryValid) {
                invalidReason = "商品所属分类已失效";
            } else if (!stockEnough) {
                invalidReason = "库存不足，仅剩 " + stock + " 份";
            } else if (!priceMatched) {
                invalidReason = "商品价格已变更";
            } else if (!specMatched) {
                invalidReason = "商品规格已变更";
            }

            itemResults.add(resultBuilder
                    .productName(product.getName())
                    .coverImage(com.freshmeat.util.FileUrlUtil.toRelative(product.getCoverImage()))
                    .price(scale(dbPrice))
                    .subtotal(scale(subtotal))
                    .stock(stock)
                    .onSale(onSale)
                    .categoryId(product.getCategoryId())
                    .categoryValid(categoryValid)
                    .priceMatched(priceMatched)
                    .specMatched(specMatched)
                    .purchasable(purchasable)
                    .valid(valid)
                    .invalidReason(invalidReason)
                    .build());
        }

        // ---------- 6. 期望送达/自取时间校验 ----------
        String expectedTimeText = null;
        if (StringUtils.hasText(dto.getExpectedTime())) {
            try {
                LocalDateTime expectedTime = DeliveryTimeUtil.parseAndValidate(dto.getExpectedTime(), shop, deliveryType);
                expectedTimeText = expectedTime == null ? null
                        : expectedTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            } catch (IllegalArgumentException e) {
                addIssue(issues, OrderPrecheckVO.CODE_EXPECTED_TIME_INVALID, e.getMessage(), null);
            }
        }

        // ---------- 7. 收货地址 / 配送范围 / 起送价 / 运费校验 ----------
        BigDecimal deliveryFee = BigDecimal.ZERO;
        BigDecimal minOrderAmount = BigDecimal.ZERO;
        BigDecimal shortAmount = BigDecimal.ZERO;
        double distance = 0D;
        boolean canDeliver = true;

        if (deliveryType != null
                && (deliveryType == OrderInfo.DELIVERY_MERCHANT || deliveryType == OrderInfo.DELIVERY_EXPRESS)) {
            if (!StringUtils.hasText(dto.getAddressId())) {
                canDeliver = false;
                addIssue(issues, OrderPrecheckVO.CODE_ADDRESS_REQUIRED, "请选择收货地址", null);
            } else {
                UserAddress address = userAddressMapper.selectById(dto.getAddressId());
                if (address == null || !userId.equals(address.getUserId())) {
                    canDeliver = false;
                    addIssue(issues, OrderPrecheckVO.CODE_ADDRESS_INVALID, "收货地址不存在，请重新选择", null);
                } else if (deliveryType == OrderInfo.DELIVERY_EXPRESS) {
                    // 快递配送：按店铺快递配置与地址计算运费（特殊地区可加价或停发）
                    if (shop != null) {
                        ExpressCheckVO expressCheck = shopInfoService.calculateExpress(shop, address);
                        canDeliver = Boolean.TRUE.equals(expressCheck.getCanDeliver());
                        deliveryFee = expressCheck.getFee() != null ? expressCheck.getFee() : BigDecimal.ZERO;
                        if (!canDeliver) {
                            addIssue(issues, OrderPrecheckVO.CODE_DELIVERY_NOT_SUPPORTED,
                                    expressCheck.getMessage(), null);
                        }
                    }
                } else if (address.getLongitude() == null || address.getLatitude() == null) {
                    canDeliver = false;
                    addIssue(issues, OrderPrecheckVO.CODE_ADDRESS_INVALID, "该地址缺少经纬度定位，请重新编辑完善", null);
                } else if (shop != null && (shop.getLongitude() == null || shop.getLatitude() == null)) {
                    canDeliver = false;
                    addIssue(issues, OrderPrecheckVO.CODE_DELIVERY_NOT_SUPPORTED, "门店未配置定位，暂无法同城配送", null);
                } else if (shop != null) {
                    double actualDist = DistanceUtil.calculateDistance(
                            shop.getLongitude().doubleValue(), shop.getLatitude().doubleValue(),
                            address.getLongitude().doubleValue(), address.getLatitude().doubleValue());
                    DistanceCheckVO deliveryCheck = deliveryRuleService.calculateDelivery(shop, actualDist);
                    distance = deliveryCheck.getDistance() != null ? deliveryCheck.getDistance() : actualDist;
                    canDeliver = Boolean.TRUE.equals(deliveryCheck.getCanDeliver());
                    deliveryFee = deliveryCheck.getDeliveryFee() != null
                            ? deliveryCheck.getDeliveryFee() : BigDecimal.ZERO;
                    minOrderAmount = deliveryCheck.getMinOrderAmount() != null
                            ? deliveryCheck.getMinOrderAmount() : BigDecimal.ZERO;

                    if (!canDeliver) {
                        addIssue(issues, OrderPrecheckVO.CODE_DELIVERY_NOT_SUPPORTED, deliveryCheck.getMessage(), null);
                    } else if (goodsAmount.compareTo(minOrderAmount) < 0) {
                        shortAmount = minOrderAmount.subtract(goodsAmount);
                        addIssue(issues, OrderPrecheckVO.CODE_MIN_ORDER_NOT_REACHED, String.format(
                                "当前配送距离约 %.2f 公里，未达到该距离起送金额(¥%.2f)，当前商品金额为¥%.2f，还差¥%.2f 起送",
                                distance, minOrderAmount.doubleValue(), goodsAmount.doubleValue(),
                                shortAmount.doubleValue()), null);
                    }
                }
            }
        }

        // ---------- 8. 运费 / 金额一致性校验（与页面展示比对） ----------
        BigDecimal payAmount = goodsAmount.add(deliveryFee);
        if (dto.getGoodsAmount() != null && !sameAmount(dto.getGoodsAmount(), goodsAmount)) {
            addIssue(issues, OrderPrecheckVO.CODE_AMOUNT_CHANGED, String.format(
                    "商品金额已变化，当前应为 ¥%.2f（页面显示 ¥%.2f），请刷新后重新提交",
                    goodsAmount.doubleValue(), dto.getGoodsAmount().doubleValue()), null);
        }
        if (dto.getDeliveryFee() != null && !sameAmount(dto.getDeliveryFee(), deliveryFee)) {
            deliveryFeeChanged = true;
            addIssue(issues, OrderPrecheckVO.CODE_DELIVERY_FEE_CHANGED, String.format(
                    "配送费/快递费已更新，当前运费为 ¥%.2f（页面显示 ¥%.2f），请确认后重新提交",
                    deliveryFee.doubleValue(), dto.getDeliveryFee().doubleValue()), null);
        }
        if (dto.getPayAmount() != null && !sameAmount(dto.getPayAmount(), payAmount)) {
            addIssue(issues, OrderPrecheckVO.CODE_AMOUNT_CHANGED, String.format(
                    "实付金额已变化，当前应为 ¥%.2f（页面显示 ¥%.2f），请刷新后重新提交",
                    payAmount.doubleValue(), dto.getPayAmount().doubleValue()), null);
        }

        OrderPrecheckVO.Issue firstIssue = issues.isEmpty() ? null : issues.get(0);
        return OrderPrecheckVO.builder()
                .canOrder(issues.isEmpty())
                .code(firstIssue == null ? null : firstIssue.getCode())
                .message(firstIssue == null ? "校验通过" : firstIssue.getMessage())
                .issues(issues)
                .items(itemResults)
                .priceChanged(priceChanged)
                .specChanged(specChanged)
                .deliveryFeeChanged(deliveryFeeChanged)
                .goodsAmount(scale(goodsAmount))
                .deliveryFee(scale(deliveryFee))
                .payAmount(scale(payAmount))
                .minOrderAmount(scale(minOrderAmount))
                .shortAmount(scale(shortAmount))
                .distance(distance)
                .canDeliver(canDeliver)
                .expectedTime(expectedTimeText)
                .shopStatus(shop == null ? null : shop.getDisplayStatus())
                .shopStatusText(shop == null ? null : shop.getStatusText())
                .build();
    }

    /** 校验商品所属分类是否仍然存在且启用 */
    private Category checkCategory(Product product, Map<String, Category> categoryCache,
                                  List<OrderPrecheckVO.Issue> issues) {
        String categoryId = normalize(product.getCategoryId());
        if (!StringUtils.hasText(categoryId)) {
            return null;
        }
        Category category = categoryCache.get(categoryId);
        if (category == null) {
            category = categoryMapper.selectById(categoryId);
            if (category != null) {
                categoryCache.put(categoryId, category);
            }
        }
        if (category == null) {
            addIssue(issues, OrderPrecheckVO.CODE_CATEGORY_DELETED,
                    "商品「" + product.getName() + "」所属分类已被删除，请返回购物车刷新后重新下单",
                    product.getId());
            categoryCache.put(categoryId, null);
            return null;
        }
        if (category.getStatus() != null && category.getStatus() != 1) {
            addIssue(issues, OrderPrecheckVO.CODE_CATEGORY_DISABLED,
                    "商品「" + product.getName() + "」所属分类「" + category.getName() + "」已停用，请返回购物车移除后重新下单",
                    product.getId());
        }
        return category;
    }

    /**
     * 校验所选规格是否仍然属于商品可选规格
     *
     * <p>商品未配置规格（specs_json 为空/异常）或规格为空时不做限制，避免误伤历史购物车数据。</p>
     */
    private boolean isSpecMatched(Product product, String specInfo) {
        if (product == null || !StringUtils.hasText(specInfo)) {
            return true;
        }
        String spec = specInfo.trim();
        String unit = product.getUnit() == null ? null : product.getUnit().trim();
        // 未选择加工方式时前端会用计价单位兜底，属于合法值
        if (StringUtils.hasText(unit) && unit.equals(spec)) {
            return true;
        }
        String specsJson = product.getSpecsJson();
        if (!StringUtils.hasText(specsJson) || "[]".equals(specsJson.trim())) {
            return true;
        }

        List<Map<String, Object>> groups;
        try {
            groups = SPECS_PARSER.readValue(specsJson, new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (Exception e) {
            // 规格配置异常时不做拦截，交由下单流程兜底
            return true;
        }
        if (groups == null || groups.isEmpty()) {
            return true;
        }

        Set<String> methodNames = new HashSet<>();
        Set<String> optionNames = new HashSet<>();
        for (Map<String, Object> group : groups) {
            if (group == null) {
                continue;
            }
            Object name = group.get("name");
            if (name != null) {
                methodNames.add(String.valueOf(name).trim());
            }
            Object options = group.get("options");
            if (options instanceof List) {
                for (Object option : (List<?>) options) {
                    if (option != null) {
                        optionNames.add(String.valueOf(option).trim());
                    }
                }
            }
        }
        if (methodNames.isEmpty() && optionNames.isEmpty()) {
            return true;
        }
        if (methodNames.contains(spec) || optionNames.contains(spec)) {
            return true;
        }

        // 规格文案为「加工方式 · 具体规格」的组合形式，逐段匹配
        boolean allMatched = true;
        for (String token : spec.split("[·・•|]")) {
            String part = token.trim();
            if (part.isEmpty()) {
                continue;
            }
            if (!methodNames.contains(part) && !optionNames.contains(part)) {
                allMatched = false;
                break;
            }
        }
        return allMatched;
    }

    /** 同一商品在各规格上的总购买数量 */
    private int totalQuantityOf(String productId, List<OrderPrecheckDTO.PrecheckItemDTO> items) {
        int total = 0;
        for (OrderPrecheckDTO.PrecheckItemDTO item : items) {
            if (item != null && productId.equals(item.getProductId()) && item.getQuantity() != null) {
                total += item.getQuantity();
            }
        }
        return total;
    }

    private void addIssue(List<OrderPrecheckVO.Issue> issues, String code, String message, String productId) {
        issues.add(OrderPrecheckVO.Issue.builder()
                .code(code)
                .message(message)
                .productId(productId)
                .build());
    }

    /** 金额按分比较，容忍浮点/四舍五入误差 */
    private boolean sameAmount(BigDecimal a, BigDecimal b) {
        BigDecimal left = a == null ? BigDecimal.ZERO : a;
        BigDecimal right = b == null ? BigDecimal.ZERO : b;
        return left.subtract(right).abs().compareTo(new BigDecimal("0.01")) < 0;
    }

    private BigDecimal scale(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    private String normalize(String value) {
        return value == null ? null : value.trim();
    }

    private OrderPrecheckVO failFast(List<OrderPrecheckVO.Issue> issues) {
        OrderPrecheckVO.Issue first = issues.isEmpty() ? null : issues.get(0);
        return OrderPrecheckVO.builder()
                .canOrder(false)
                .code(first == null ? null : first.getCode())
                .message(first == null ? "订单校验未通过" : first.getMessage())
                .issues(issues)
                .items(Collections.emptyList())
                .priceChanged(false)
                .specChanged(false)
                .deliveryFeeChanged(false)
                .goodsAmount(BigDecimal.ZERO)
                .deliveryFee(BigDecimal.ZERO)
                .payAmount(BigDecimal.ZERO)
                .minOrderAmount(BigDecimal.ZERO)
                .shortAmount(BigDecimal.ZERO)
                .distance(0D)
                .canDeliver(false)
                .build();
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

        // 期望送达/自取时间（顾客下单时选择，掌柜按此排单）
        if (order.getExpectedTime() != null) {
            vo.setExpectedTime(order.getExpectedTime());
            vo.setExpectedTimeStr(order.getExpectedTime().format(DateTimeFormatter.ofPattern("MM-dd HH:mm")));
            vo.setExpectedTimeDesc(order.getDeliveryType() != null
                    && order.getDeliveryType() == OrderInfo.DELIVERY_PICKUP ? "期望到店自取" : "期望送达");
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
            // 未绑定任何店铺的账号不允许核销，避免不限定店铺而越权核销其他门店订单
            if (org.springframework.util.CollectionUtils.isEmpty(adminShopIds)) {
                throw new BusinessException(403, "当前账号未绑定任何店铺，无法核销提货码");
            }
            qw.in(OrderInfo::getShopId, adminShopIds);
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
