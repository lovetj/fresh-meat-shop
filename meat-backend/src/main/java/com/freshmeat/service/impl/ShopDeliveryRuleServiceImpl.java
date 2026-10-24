package com.freshmeat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.freshmeat.dto.DistanceCheckVO;
import com.freshmeat.entity.ShopDeliveryRule;
import com.freshmeat.entity.ShopInfo;
import com.freshmeat.exception.BusinessException;
import com.freshmeat.mapper.ShopDeliveryRuleMapper;
import com.freshmeat.mapper.ShopInfoMapper;
import com.freshmeat.service.ShopDeliveryRuleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ShopDeliveryRuleServiceImpl extends ServiceImpl<ShopDeliveryRuleMapper, ShopDeliveryRule>
        implements ShopDeliveryRuleService {

    private final ShopInfoMapper shopInfoMapper;

    @Override
    public List<ShopDeliveryRule> listRulesByShopId(String shopId) {
        if (!StringUtils.hasText(shopId)) {
            return Collections.emptyList();
        }

        List<ShopDeliveryRule> list = this.list(new LambdaQueryWrapper<ShopDeliveryRule>()
                .eq(ShopDeliveryRule::getShopId, shopId)
                .orderByAsc(ShopDeliveryRule::getMinDistance)
                .orderByAsc(ShopDeliveryRule::getSortOrder));

        if (!CollectionUtils.isEmpty(list)) {
            return list;
        }

        // 若尚未配置任何阶梯规则，动态构建一条保底规则 (默认 0-5km, 起送20元, 配送费3元)
        ShopInfo shop = shopInfoMapper.selectById(shopId);
        if (shop != null) {
            ShopDeliveryRule defaultRule = ShopDeliveryRule.builder()
                    .id("dr_init_" + shopId)
                    .shopId(shopId)
                    .minDistance(BigDecimal.ZERO)
                    .maxDistance(new BigDecimal("5.00"))
                    .minOrderAmount(new BigDecimal("20.00"))
                    .deliveryFee(new BigDecimal("3.00"))
                    .sortOrder(1)
                    .createTime(LocalDateTime.now())
                    .updateTime(LocalDateTime.now())
                    .build();
            return Collections.singletonList(defaultRule);
        }

        return Collections.emptyList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveShopRules(String shopId, List<ShopDeliveryRule> rules) {
        if (!StringUtils.hasText(shopId)) {
            throw new BusinessException("商铺ID不能为空");
        }
        if (CollectionUtils.isEmpty(rules)) {
            throw new BusinessException("至少需要配置一条同城配送规则");
        }

        ShopInfo shop = shopInfoMapper.selectById(shopId);
        if (shop == null) {
            throw new BusinessException("目标商铺不存在");
        }

        // 校验并清洗规则
        List<ShopDeliveryRule> cleanList = new ArrayList<>();
        int order = 1;
        for (ShopDeliveryRule r : rules) {
            if (r.getMinDistance() == null || r.getMinDistance().compareTo(BigDecimal.ZERO) < 0) {
                throw new BusinessException("配送起始距离必须大于或等于0");
            }

            // 已移除「不限距离」配置：每一档都必须填写明确的截止距离
            if (r.getMaxDistance() == null || r.getMaxDistance().compareTo(BigDecimal.ZERO) <= 0) {
                throw new BusinessException(String.format("第 %d 档配送规则缺少截止距离，请填写具体公里数", order));
            }
            if (r.getMaxDistance().compareTo(r.getMinDistance()) <= 0) {
                throw new BusinessException(String.format("配送截止距离(%.2f)必须大于起始距离(%.2f)",
                        r.getMaxDistance().doubleValue(), r.getMinDistance().doubleValue()));
            }

            if (r.getMinOrderAmount() == null || r.getMinOrderAmount().compareTo(BigDecimal.ZERO) < 0) {
                r.setMinOrderAmount(BigDecimal.ZERO);
            }
            if (r.getDeliveryFee() == null || r.getDeliveryFee().compareTo(BigDecimal.ZERO) < 0) {
                r.setDeliveryFee(BigDecimal.ZERO);
            }

            r.setId(null); // 由 MyBatis-Plus 自动生成新UUID
            r.setShopId(shopId);
            r.setSortOrder(order++);
            r.setCreateTime(LocalDateTime.now());
            r.setUpdateTime(LocalDateTime.now());
            cleanList.add(r);
        }

        // 按起始距离从小到大排序
        cleanList.sort(Comparator.comparing(ShopDeliveryRule::getMinDistance));

        // 重新分配序号
        for (int i = 0; i < cleanList.size(); i++) {
            cleanList.get(i).setSortOrder(i + 1);
        }

        // 删除旧规则
        this.remove(new LambdaQueryWrapper<ShopDeliveryRule>().eq(ShopDeliveryRule::getShopId, shopId));

        // 批量保存新规则
        this.saveBatch(cleanList);
    }

    @Override
    public DistanceCheckVO calculateDelivery(ShopInfo shop, double actualDistance) {
        if (shop == null) {
            throw new BusinessException("商铺不存在");
        }

        // 保留两位小数
        double roundedDist = BigDecimal.valueOf(actualDistance).setScale(2, RoundingMode.HALF_UP).doubleValue();

        // 同城商家配送开关关闭：不参与测距计费，引导顾客选择到店自取或快递配送
        if (shop.getMerchantDeliveryEnabled() == null || shop.getMerchantDeliveryEnabled() != 1) {
            return DistanceCheckVO.builder()
                    .distance(roundedDist)
                    .maxDeliveryDistance(0D)
                    .isUnlimitedDistance(false)
                    .canDeliver(false)
                    .deliveryFee(BigDecimal.ZERO)
                    .minOrderAmount(BigDecimal.ZERO)
                    .deliveryRules(Collections.emptyList())
                    .message("本店暂未开通同城商家配送，可选择「到店自取」或「快递配送」下单")
                    .build();
        }

        List<ShopDeliveryRule> rules = listRulesByShopId(shop.getId());
        // 已取消「不限距离」配置：历史遗留的无截止距离规则不再参与计算
        if (!CollectionUtils.isEmpty(rules)) {
            rules = rules.stream()
                    .filter(rule -> rule.getMinDistance() != null)
                    .filter(rule -> rule.getMaxDistance() != null
                            && rule.getMaxDistance().compareTo(BigDecimal.ZERO) > 0)
                    .sorted(Comparator.comparing(ShopDeliveryRule::getMinDistance))
                    .collect(java.util.stream.Collectors.toList());
        }

        if (CollectionUtils.isEmpty(rules)) {
            // 无有效规则时使用默认范围兜底 (默认 5km 内起送15元，配送费3元)
            double maxDist = 5.0;
            boolean canDeliver = roundedDist <= maxDist;

            String msg = canDeliver
                    ? String.format("距「%s」约 %.2f 公里，在商家配送范围内", shop.getName(), roundedDist)
                    : String.format("距「%s」约 %.2f 公里，超出最大配送范围(%.1f公里)，建议选择「到店自取」或「快递配送」", shop.getName(), roundedDist, maxDist);

            return DistanceCheckVO.builder()
                    .distance(roundedDist)
                    .maxDeliveryDistance(maxDist)
                    .isUnlimitedDistance(false)
                    .canDeliver(canDeliver)
                    .deliveryFee(new BigDecimal("3.00"))
                    .minOrderAmount(new BigDecimal("15.00"))
                    .deliveryRules(Collections.emptyList())
                    .message(msg)
                    .build();
        }

        // 整体最大配送半径 = 最后一档的截止距离
        double maxDist = rules.get(rules.size() - 1).getMaxDistance().doubleValue();

        // 超出所有阶梯：不提供同城配送，引导到店自取或快递
        if (roundedDist > maxDist) {
            return DistanceCheckVO.builder()
                    .distance(roundedDist)
                    .maxDeliveryDistance(maxDist)
                    .isUnlimitedDistance(false)
                    .canDeliver(false)
                    .deliveryFee(BigDecimal.ZERO)
                    .minOrderAmount(BigDecimal.ZERO)
                    .deliveryRules(rules)
                    .message(String.format("距「%s」约 %.2f 公里，超出本店最大同城配送范围(%.1f公里)，建议选择「到店自取」或「快递配送」",
                            shop.getName(), roundedDist, maxDist))
                    .build();
        }

        // 命中区间阶梯（未命中时取最近的一档兜底）
        ShopDeliveryRule matchedRule = null;
        for (ShopDeliveryRule rule : rules) {
            if (roundedDist <= rule.getMaxDistance().doubleValue()) {
                matchedRule = rule;
                break;
            }
        }
        if (matchedRule == null) {
            matchedRule = rules.get(rules.size() - 1);
        }

        BigDecimal deliveryFee = matchedRule.getDeliveryFee() != null ? matchedRule.getDeliveryFee() : BigDecimal.ZERO;
        BigDecimal minOrderAmount = matchedRule.getMinOrderAmount() != null ? matchedRule.getMinOrderAmount() : BigDecimal.ZERO;

        String msg = String.format("距「%s」约 %.2f 公里 (命中 %.1f-%.1f公里配送阶梯)：起送金额 ¥%.2f，配送费 ¥%.2f",
                shop.getName(), roundedDist,
                matchedRule.getMinDistance().doubleValue(),
                matchedRule.getMaxDistance().doubleValue(),
                minOrderAmount.doubleValue(),
                deliveryFee.doubleValue());

        return DistanceCheckVO.builder()
                .distance(roundedDist)
                .maxDeliveryDistance(maxDist)
                .isUnlimitedDistance(false)
                .canDeliver(true)
                .deliveryFee(deliveryFee)
                .minOrderAmount(minOrderAmount)
                .matchedRule(matchedRule)
                .deliveryRules(rules)
                .message(msg)
                .build();
    }
}
