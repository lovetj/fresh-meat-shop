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

            // 支持不限距离：maxDistance为null或<=0均表示无上限/不限距离
            if (r.getMaxDistance() != null && r.getMaxDistance().compareTo(BigDecimal.ZERO) > 0) {
                if (r.getMaxDistance().compareTo(r.getMinDistance()) <= 0) {
                    throw new BusinessException(String.format("配送截止距离(%.2f)必须大于起始距离(%.2f)",
                            r.getMaxDistance().doubleValue(), r.getMinDistance().doubleValue()));
                }
            } else {
                r.setMaxDistance(null); // 统一将无上限置为null
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

        // 重新分配序号，并校验无上限规则只允许在最后一档
        for (int i = 0; i < cleanList.size(); i++) {
            cleanList.get(i).setSortOrder(i + 1);
            if (cleanList.get(i).getMaxDistance() == null && i < cleanList.size() - 1) {
                throw new BusinessException("不限距离(无上限)的阶梯规则必须作为最后一个阶梯配置");
            }
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

        List<ShopDeliveryRule> rules = listRulesByShopId(shop.getId());
        if (CollectionUtils.isEmpty(rules)) {
            // 无规则时使用默认阶梯配置兜底 (默认 5km 内起送15元，配送费3元)
            double maxDist = 5.0;
            boolean canDeliver = roundedDist <= maxDist;

            String msg = canDeliver
                    ? String.format("距「%s」约 %.2f 公里，在商家配送范围内", shop.getName(), roundedDist)
                    : String.format("距「%s」约 %.2f 公里，超出最大配送范围(%.1f公里)，建议选择「自取」", shop.getName(), roundedDist, maxDist);

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

        // 规则按 minDistance 从小到大排序
        rules.sort(Comparator.comparing(ShopDeliveryRule::getMinDistance));

        // 检查是否存在不限距离/无上限阶梯
        ShopDeliveryRule unlimitedRule = null;
        for (ShopDeliveryRule rule : rules) {
            if (rule.getMaxDistance() == null || rule.getMaxDistance().compareTo(BigDecimal.ZERO) <= 0) {
                unlimitedRule = rule;
                break;
            }
        }

        boolean isUnlimited = unlimitedRule != null;

        // 若全部都有截止距离，计算整体最大配送半径
        if (!isUnlimited) {
            BigDecimal maxOverallDistance = rules.stream()
                    .map(ShopDeliveryRule::getMaxDistance)
                    .filter(java.util.Objects::nonNull)
                    .max(Comparator.naturalOrder())
                    .orElse(new BigDecimal("5.00"));
            double maxDist = maxOverallDistance.doubleValue();

            // 超出有限阶梯的最大配送范围
            if (roundedDist > maxDist) {
                String msg = String.format("距「%s」约 %.2f 公里，超出本店最大同城配送范围(%.1f公里)，建议选择「到店自取」或切换至更近的分店",
                        shop.getName(), roundedDist, maxDist);
                return DistanceCheckVO.builder()
                        .distance(roundedDist)
                        .maxDeliveryDistance(maxDist)
                        .isUnlimitedDistance(false)
                        .canDeliver(false)
                        .deliveryFee(BigDecimal.ZERO)
                        .minOrderAmount(BigDecimal.ZERO)
                        .deliveryRules(rules)
                        .message(msg)
                        .build();
            }
        }

        // 智能匹配对应阶梯
        ShopDeliveryRule matchedRule = null;

        // 1. 优先尝试匹配有确切截止距离的有限阶梯 [min, max]
        for (ShopDeliveryRule rule : rules) {
            if (rule.getMaxDistance() != null && rule.getMaxDistance().compareTo(BigDecimal.ZERO) > 0) {
                double rMin = rule.getMinDistance().doubleValue();
                double rMax = rule.getMaxDistance().doubleValue();
                if (roundedDist <= rMax && roundedDist >= rMin) {
                    matchedRule = rule;
                    break;
                }
            }
        }

        // 2. 若未精确命中有限阶梯且存在无上限规则
        if (matchedRule == null && isUnlimited) {
            matchedRule = unlimitedRule;
        }

        // 3. 若仍未命中，尝试找最接近的阶梯兜底
        if (matchedRule == null) {
            for (ShopDeliveryRule rule : rules) {
                if (rule.getMaxDistance() != null && rule.getMaxDistance().doubleValue() >= roundedDist) {
                    matchedRule = rule;
                    break;
                }
            }
        }

        // 4. 仍未命中则取最后一条
        if (matchedRule == null) {
            matchedRule = rules.get(rules.size() - 1);
        }

        BigDecimal deliveryFee = matchedRule.getDeliveryFee() != null ? matchedRule.getDeliveryFee() : BigDecimal.ZERO;
        BigDecimal minOrderAmount = matchedRule.getMinOrderAmount() != null ? matchedRule.getMinOrderAmount() : BigDecimal.ZERO;

        String msg;
        if (matchedRule.getMaxDistance() == null || matchedRule.getMaxDistance().compareTo(BigDecimal.ZERO) <= 0) {
            msg = String.format("距「%s」约 %.2f 公里 (命中 %.1f公里以上不限距离阶梯)：起送金额 ¥%.2f，配送费 ¥%.2f",
                    shop.getName(), roundedDist,
                    matchedRule.getMinDistance().doubleValue(),
                    minOrderAmount.doubleValue(),
                    deliveryFee.doubleValue());
        } else {
            msg = String.format("距「%s」约 %.2f 公里 (命中 %.1f-%.1f公里配送阶梯)：起送金额 ¥%.2f，配送费 ¥%.2f",
                    shop.getName(), roundedDist,
                    matchedRule.getMinDistance().doubleValue(),
                    matchedRule.getMaxDistance().doubleValue(),
                    minOrderAmount.doubleValue(),
                    deliveryFee.doubleValue());
        }

        return DistanceCheckVO.builder()
                .distance(roundedDist)
                .maxDeliveryDistance(isUnlimited ? 0.0 : rules.stream().map(ShopDeliveryRule::getMaxDistance).filter(java.util.Objects::nonNull).max(Comparator.naturalOrder()).orElse(BigDecimal.ZERO).doubleValue())
                .isUnlimitedDistance(isUnlimited)
                .canDeliver(true)
                .deliveryFee(deliveryFee)
                .minOrderAmount(minOrderAmount)
                .matchedRule(matchedRule)
                .deliveryRules(rules)
                .message(msg)
                .build();
    }
}
