package com.freshmeat.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.freshmeat.dto.DistanceCheckVO;
import com.freshmeat.entity.ShopDeliveryRule;
import com.freshmeat.entity.ShopInfo;

import java.util.List;

public interface ShopDeliveryRuleService extends IService<ShopDeliveryRule> {

    /**
     * 查询指定店铺的所有同城配送多阶梯规则（按 min_distance 升序）
     */
    List<ShopDeliveryRule> listRulesByShopId(String shopId);

    /**
     * 批量保存/更新商铺同城配送多阶梯规则
     */
    void saveShopRules(String shopId, List<ShopDeliveryRule> rules);

    /**
     * 根据商铺和实际距离，智能匹配配送阶梯规则
     * @param shop 商铺实体
     * @param actualDistance 实际测算距离 (km)
     * @return 测距与费用判定结果
     */
    DistanceCheckVO calculateDelivery(ShopInfo shop, double actualDistance);
}
