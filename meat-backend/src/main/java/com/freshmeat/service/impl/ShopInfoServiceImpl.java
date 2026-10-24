package com.freshmeat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.freshmeat.common.PageResult;
import com.freshmeat.dto.DistanceCheckDTO;
import com.freshmeat.dto.DistanceCheckVO;
import com.freshmeat.dto.ExpressCheckVO;
import com.freshmeat.dto.ShopInfoDTO;
import com.freshmeat.dto.ShopQueryDTO;
import com.freshmeat.entity.ShopAdmin;
import com.freshmeat.entity.ShopDeliveryRule;
import com.freshmeat.entity.ShopExpressRegion;
import com.freshmeat.entity.ShopInfo;
import com.freshmeat.entity.User;
import com.freshmeat.entity.UserAddress;
import com.freshmeat.exception.BusinessException;
import com.freshmeat.mapper.ShopAdminMapper;
import com.freshmeat.mapper.ShopInfoMapper;
import com.freshmeat.mapper.UserAddressMapper;
import com.freshmeat.mapper.UserMapper;
import com.freshmeat.service.ShopInfoService;
import com.freshmeat.util.DistanceUtil;
import com.freshmeat.util.SecurityContextUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ShopInfoServiceImpl extends ServiceImpl<ShopInfoMapper, ShopInfo> implements ShopInfoService {

    private final ShopAdminMapper shopAdminMapper;
    private final UserMapper userMapper;
    private final UserAddressMapper userAddressMapper;
    private final com.freshmeat.service.ShopDeliveryRuleService deliveryRuleService;

    @Override
    public ShopInfo getShopInfo() {
        return getShopInfo(null);
    }

    @Override
    public ShopInfo getShopInfo(String shopId) {
        ShopInfo targetShop = null;
        if (StringUtils.hasText(shopId)) {
            targetShop = this.getById(shopId);
        }

        if (targetShop == null) {
            // 默认策略：优先选择正在营业且排序最高的店铺
            List<ShopInfo> shops = this.list(new LambdaQueryWrapper<ShopInfo>()
                    .orderByDesc(ShopInfo::getStatus)
                    .orderByDesc(ShopInfo::getSortOrder)
                    .orderByAsc(ShopInfo::getCreateTime));

            if (!CollectionUtils.isEmpty(shops)) {
                targetShop = shops.get(0);
            }
        }

        // 若库中完全无数据，提供兜底创建
        if (targetShop == null) {
            targetShop = ShopInfo.builder()
                    .id("s_shop_000000000000000000000000001")
                    .name("维琼鲜肉铺(科技园旗舰店)")
                    .notice("每日清晨现宰直达，同城专人极速送达！")
                    .phone("13800138000")
                    .businessHours("06:30 - 20:00")
                    .address("深圳市南山区科兴科学园A01")
                    .longitude(new BigDecimal("113.9435000"))
                    .latitude(new BigDecimal("22.5492000"))
                    .status(1)
                    .sortOrder(100)
                    .build();
            this.save(targetShop);
        }

        // 统一附带加载配送配置（同城阶梯规则、同城配送开通状态、快递特殊地区）
        fillShopExtras(targetShop);
        return targetShop;
    }

    @Override
    public List<ShopInfo> listCustomerShops(BigDecimal longitude, BigDecimal latitude) {
        List<ShopInfo> shops = this.list(new LambdaQueryWrapper<ShopInfo>()
                .orderByDesc(ShopInfo::getStatus)
                .orderByDesc(ShopInfo::getSortOrder));

        if (CollectionUtils.isEmpty(shops)) {
            return Collections.singletonList(getShopInfo());
        }

        // 如果用户提供了经纬度，计算每个店铺到用户的直线距离
        if (longitude != null && latitude != null) {
            double uLng = longitude.doubleValue();
            double uLat = latitude.doubleValue();
            for (ShopInfo shop : shops) {
                if (shop.getLongitude() != null && shop.getLatitude() != null) {
                    double dist = DistanceUtil.calculateDistance(
                            shop.getLongitude().doubleValue(),
                            shop.getLatitude().doubleValue(),
                            uLng,
                            uLat
                    );
                    BigDecimal bd = BigDecimal.valueOf(dist).setScale(2, RoundingMode.HALF_UP);
                    shop.setDistance(bd.doubleValue());
                } else {
                    shop.setDistance(9999.0);
                }
            }

            // 优先营业中，其次按距离由近到远排序
            shops.sort((a, b) -> {
                int statusComp = Integer.compare(b.getStatus() != null ? b.getStatus() : 0, a.getStatus() != null ? a.getStatus() : 0);
                if (statusComp != 0) {
                    return statusComp;
                }
                double d1 = a.getDistance() != null ? a.getDistance() : 9999.0;
                double d2 = b.getDistance() != null ? b.getDistance() : 9999.0;
                return Double.compare(d1, d2);
            });
        }

        fillShopExtras(shops);

        return shops;
    }

    @Override
    public PageResult<ShopInfo> pageCustomerShops(ShopQueryDTO queryDTO) {
        ShopQueryDTO query = queryDTO != null ? queryDTO : new ShopQueryDTO();
        long pageNum = query.getPageNum() != null && query.getPageNum() > 0 ? query.getPageNum() : 1L;
        long pageSize = query.getPageSize() != null && query.getPageSize() > 0 ? query.getPageSize() : 10L;
        String keyword = StringUtils.hasText(query.getKeyword()) ? query.getKeyword().trim() : null;

        LambdaQueryWrapper<ShopInfo> qw = new LambdaQueryWrapper<>();
        if (keyword != null) {
            qw.and(w -> w.like(ShopInfo::getName, keyword).or().like(ShopInfo::getAddress, keyword));
        }
        // 营业中优先，再按排序权重；最后用 id 保证分页顺序稳定
        qw.orderByDesc(ShopInfo::getStatus)
                .orderByDesc(ShopInfo::getSortOrder)
                .orderByAsc(ShopInfo::getCreateTime)
                .orderByAsc(ShopInfo::getId);

        Page<ShopInfo> resultPage = this.page(new Page<>(pageNum, pageSize), qw);
        List<ShopInfo> records = new ArrayList<>(resultPage.getRecords());

        // 库中无数据时兜底返回默认门店（与 /shop/list 行为保持一致）
        if (records.isEmpty() && keyword == null && pageNum <= 1) {
            ShopInfo defaultShop = getShopInfo();
            if (defaultShop != null) {
                defaultShop.setDistance(9999.0);
                return PageResult.of(1L, Collections.singletonList(defaultShop), pageNum, pageSize);
            }
        }

        // 传入经纬度则计算距离，并在当前页内按「营业中优先 + 距离由近到远」排序
        if (query.getLongitude() != null && query.getLatitude() != null && !records.isEmpty()) {
            double uLng = query.getLongitude().doubleValue();
            double uLat = query.getLatitude().doubleValue();
            for (ShopInfo shop : records) {
                if (shop.getLongitude() != null && shop.getLatitude() != null) {
                    double dist = DistanceUtil.calculateDistance(
                            shop.getLongitude().doubleValue(),
                            shop.getLatitude().doubleValue(),
                            uLng,
                            uLat
                    );
                    shop.setDistance(BigDecimal.valueOf(dist).setScale(2, RoundingMode.HALF_UP).doubleValue());
                } else {
                    shop.setDistance(9999.0);
                }
            }

            records.sort((a, b) -> {
                int statusComp = Integer.compare(
                        b.getStatus() != null ? b.getStatus() : 0,
                        a.getStatus() != null ? a.getStatus() : 0);
                if (statusComp != 0) {
                    return statusComp;
                }
                double d1 = a.getDistance() != null ? a.getDistance() : 9999.0;
                double d2 = b.getDistance() != null ? b.getDistance() : 9999.0;
                return Double.compare(d1, d2);
            });
        }

        fillShopExtras(records);

        return PageResult.of(resultPage.getTotal(), records, resultPage.getCurrent(), resultPage.getSize());
    }

    @Override
    public List<ShopInfo> listAdminShops() {
        String userId = SecurityContextUtil.getUserId();
        if (userId == null) {
            throw new BusinessException(401, "请先登录");
        }

        User user = userMapper.selectById(userId);
        if (user == null || !"ADMIN".equalsIgnoreCase(user.getRole())) {
            throw new BusinessException(403, "非管理员无权访问");
        }

        // 唯一依据是 shop_admin 关联表：OWNER / ADMIN 均可切换管理，不按 role_type 过滤
        List<ShopAdmin> relations = shopAdminMapper.selectList(
                new LambdaQueryWrapper<ShopAdmin>().eq(ShopAdmin::getUserId, userId)
        );

        if (CollectionUtils.isEmpty(relations)) {
            return Collections.emptyList();
        }

        List<String> shopIds = relations.stream()
                .map(ShopAdmin::getShopId)
                .filter(StringUtils::hasText)
                .distinct()
                .collect(Collectors.toList());
        if (shopIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<ShopInfo> adminShops = this.listByIds(shopIds);
        fillShopExtras(adminShops);
        return adminShops;
    }

    /**
     * 为店铺列表补全配送配置，供掌柜后台与顾客端展示配送范围
     */
    private void fillShopExtras(List<ShopInfo> shops) {
        if (CollectionUtils.isEmpty(shops)) {
            return;
        }
        for (ShopInfo shop : shops) {
            fillShopExtras(shop);
        }
    }

    /**
     * 补全单个店铺的配送配置：
     * 1. 同城多阶梯配送规则（均为有截止距离的有限阶梯）
     * 2. 快递配送开关、基础运费与特殊地区规则
     */
    private void fillShopExtras(ShopInfo shop) {
        if (shop == null) {
            return;
        }
        List<ShopDeliveryRule> rules = deliveryRuleService.listRulesByShopId(shop.getId());
        shop.setDeliveryRules(rules);

        shop.setExpressRegions(parseExpressRegions(shop.getExpressRegionsJson()));
        if (shop.getMerchantDeliveryEnabled() == null) {
            shop.setMerchantDeliveryEnabled(1);
        }
        if (shop.getTakeoutEnabled() == null) {
            shop.setTakeoutEnabled(0);
        }
        if (shop.getExpressEnabled() == null) {
            shop.setExpressEnabled(0);
        }
        if (shop.getExpressFee() == null) {
            shop.setExpressFee(BigDecimal.ZERO);
        }
    }

    /** 解析快递特殊地区 JSON 配置，异常时降级为空列表 */
    private List<ShopExpressRegion> parseExpressRegions(String json) {
        if (!StringUtils.hasText(json)) {
            return Collections.emptyList();
        }
        try {
            List<ShopExpressRegion> list = com.alibaba.fastjson2.JSON.parseArray(json, ShopExpressRegion.class);
            return list == null ? Collections.emptyList() : list;
        } catch (Exception e) {
            log.warn("解析店铺快递特殊地区配置失败: {}", json, e);
            return Collections.emptyList();
        }
    }

    @Override
    public List<User> listShopAdmins(String shopId) {
        if (!StringUtils.hasText(shopId)) {
            return Collections.emptyList();
        }
        if (!hasShopPermission(SecurityContextUtil.getUserId(), shopId)) {
            throw new BusinessException(403, "无权查看该店铺管理员");
        }

        List<ShopAdmin> relations = shopAdminMapper.selectList(
                new LambdaQueryWrapper<ShopAdmin>().eq(ShopAdmin::getShopId, shopId)
        );
        if (CollectionUtils.isEmpty(relations)) {
            return Collections.emptyList();
        }

        List<String> userIds = relations.stream().map(ShopAdmin::getUserId).distinct().collect(Collectors.toList());
        List<User> users = userMapper.selectBatchIds(userIds);
        for (User u : users) {
            u.setPassword(null); // 脱敏密码
        }
        return users;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean bindShopAdmin(String shopId, String username, String roleType) {
        if (!StringUtils.hasText(shopId) || !StringUtils.hasText(username)) {
            throw new BusinessException("店铺ID和管理员账号不能为空");
        }
        if (!hasShopPermission(SecurityContextUtil.getUserId(), shopId)) {
            throw new BusinessException(403, "无权管理该店铺");
        }

        ShopInfo shop = this.getById(shopId);
        if (shop == null) {
            throw new BusinessException("目标店铺不存在");
        }

        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username.trim()));
        if (user == null) {
            throw new BusinessException("未找到账号为「" + username + "」的管理员，请先创建该账号");
        }

        // 确保该用户角色为 ADMIN
        if (!"ADMIN".equalsIgnoreCase(user.getRole())) {
            user.setRole("ADMIN");
            userMapper.updateById(user);
        }

        // 检查关联是否存在
        ShopAdmin exist = shopAdminMapper.selectOne(new LambdaQueryWrapper<ShopAdmin>()
                .eq(ShopAdmin::getShopId, shopId)
                .eq(ShopAdmin::getUserId, user.getId()));

        String finalRole = StringUtils.hasText(roleType) ? roleType : "ADMIN";
        if (exist != null) {
            exist.setRoleType(finalRole);
            return shopAdminMapper.updateById(exist) > 0;
        } else {
            ShopAdmin sa = ShopAdmin.builder()
                    .shopId(shopId)
                    .userId(user.getId())
                    .roleType(finalRole)
                    .createTime(LocalDateTime.now())
                    .build();
            return shopAdminMapper.insert(sa) > 0;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean unbindShopAdmin(String shopId, String userId) {
        if (!StringUtils.hasText(shopId) || !StringUtils.hasText(userId)) {
            throw new BusinessException("店铺ID和管理员ID不能为空");
        }
        if (!hasShopPermission(SecurityContextUtil.getUserId(), shopId)) {
            throw new BusinessException(403, "无权管理该店铺");
        }
        return shopAdminMapper.delete(new LambdaQueryWrapper<ShopAdmin>()
                .eq(ShopAdmin::getShopId, shopId)
                .eq(ShopAdmin::getUserId, userId)) > 0;
    }

    @Override
    public boolean hasShopPermission(String userId, String shopId) {
        if (!StringUtils.hasText(userId) || !StringUtils.hasText(shopId)) {
            return false;
        }
        User user = userMapper.selectById(userId);
        if (user == null || !"ADMIN".equalsIgnoreCase(user.getRole())) {
            return false;
        }
        // 仅以 shop_admin 关联为准，OWNER / ADMIN 权限一致
        Long count = shopAdminMapper.selectCount(new LambdaQueryWrapper<ShopAdmin>()
                .eq(ShopAdmin::getShopId, shopId)
                .eq(ShopAdmin::getUserId, userId));
        return count != null && count > 0;
    }

    @Override
    public List<String> getAdminShopIds(String userId) {
        if (!StringUtils.hasText(userId)) {
            return Collections.emptyList();
        }
        User user = userMapper.selectById(userId);
        if (user == null || !"ADMIN".equalsIgnoreCase(user.getRole())) {
            return Collections.emptyList();
        }
        // 仅以 shop_admin 关联为准，OWNER / ADMIN 权限一致
        List<ShopAdmin> list = shopAdminMapper.selectList(new LambdaQueryWrapper<ShopAdmin>().eq(ShopAdmin::getUserId, userId));
        return list.stream().map(ShopAdmin::getShopId).distinct().collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateShopInfo(ShopInfoDTO dto) {
        String currentUserId = SecurityContextUtil.getUserId();
        String targetShopId;
        if (StringUtils.hasText(dto.getId())) {
            // 权限检查
            if (!hasShopPermission(currentUserId, dto.getId())) {
                throw new BusinessException(403, "无权管理该店铺");
            }
            ShopInfo exist = this.getById(dto.getId());
            if (exist == null) {
                throw new BusinessException("待修改商铺不存在");
            }
            // 配送能力配置：未传的字段保持原值，避免旧客户端保存时把配送能力清空
            Integer oldMerchantEnabled = exist.getMerchantDeliveryEnabled();
            Integer oldTakeoutEnabled = exist.getTakeoutEnabled();
            Integer oldExpressEnabled = exist.getExpressEnabled();
            BigDecimal oldExpressFee = exist.getExpressFee();
            String oldExpressRegions = exist.getExpressRegionsJson();

            BeanUtils.copyProperties(dto, exist);
            if (dto.getMerchantDeliveryEnabled() == null) {
                exist.setMerchantDeliveryEnabled(oldMerchantEnabled == null ? 1 : oldMerchantEnabled);
            }
            if (dto.getTakeoutEnabled() == null) {
                exist.setTakeoutEnabled(oldTakeoutEnabled == null ? 0 : oldTakeoutEnabled);
            }
            if (dto.getExpressEnabled() == null) {
                exist.setExpressEnabled(oldExpressEnabled == null ? 0 : oldExpressEnabled);
            }
            if (dto.getExpressFee() == null) {
                exist.setExpressFee(oldExpressFee);
            }
            if (dto.getExpressRegions() != null) {
                exist.setExpressRegionsJson(com.alibaba.fastjson2.JSON.toJSONString(dto.getExpressRegions()));
            }
            this.updateById(exist);
            targetShopId = exist.getId();
        } else {
            // 新增店铺
            ShopInfo newShop = new ShopInfo();
            BeanUtils.copyProperties(dto, newShop);
            if (newShop.getSortOrder() == null) {
                newShop.setSortOrder(0);
            }
            if (newShop.getMerchantDeliveryEnabled() == null) {
                newShop.setMerchantDeliveryEnabled(1);
            }
            if (newShop.getTakeoutEnabled() == null) {
                newShop.setTakeoutEnabled(0);
            }
            if (newShop.getExpressEnabled() == null) {
                newShop.setExpressEnabled(0);
            }
            if (newShop.getExpressFee() == null) {
                newShop.setExpressFee(BigDecimal.ZERO);
            }
            newShop.setExpressRegionsJson(com.alibaba.fastjson2.JSON.toJSONString(
                    dto.getExpressRegions() == null ? Collections.emptyList() : dto.getExpressRegions()));
            boolean saved = this.save(newShop);
            if (saved && currentUserId != null) {
                // 自动绑定当前管理员为该店铺的 OWNER
                ShopAdmin sa = ShopAdmin.builder()
                        .shopId(newShop.getId())
                        .userId(currentUserId)
                        .roleType("OWNER")
                        .createTime(LocalDateTime.now())
                        .build();
                shopAdminMapper.insert(sa);
            }
            targetShopId = newShop.getId();
        }

        // 保存多阶梯配送规则
        if (!CollectionUtils.isEmpty(dto.getDeliveryRules())) {
            deliveryRuleService.saveShopRules(targetShopId, dto.getDeliveryRules());
        }
        return true;
    }

    @Override
    public DistanceCheckVO checkDeliveryDistance(DistanceCheckDTO dto) {
        ShopInfo shop = getShopInfo(dto.getShopId());
        if (shop.getLongitude() == null || shop.getLatitude() == null) {
            throw new BusinessException("商铺未设置地理坐标定位");
        }

        double shopLng = shop.getLongitude().doubleValue();
        double shopLat = shop.getLatitude().doubleValue();
        double userLng = dto.getLongitude().doubleValue();
        double userLat = dto.getLatitude().doubleValue();

        // 计算球面距离 (km)
        double actualDistance = DistanceUtil.calculateDistance(shopLng, shopLat, userLng, userLat);

        // 委托多阶梯配送规则计算器
        return deliveryRuleService.calculateDelivery(shop, actualDistance);
    }

    @Override
    public ExpressCheckVO checkExpressDelivery(String shopId, String addressId) {
        String userId = SecurityContextUtil.getUserId();
        if (userId == null) {
            throw new BusinessException(401, "请先登录");
        }
        if (!StringUtils.hasText(addressId)) {
            throw new BusinessException("请选择收货地址");
        }
        UserAddress address = userAddressMapper.selectById(addressId);
        if (address == null || !userId.equals(address.getUserId())) {
            throw new BusinessException("收货地址不存在");
        }
        return calculateExpress(getShopInfo(shopId), address);
    }

    @Override
    public ExpressCheckVO calculateExpress(ShopInfo shop, UserAddress address) {
        if (shop == null || address == null) {
            return ExpressCheckVO.builder()
                    .canDeliver(false)
                    .fee(BigDecimal.ZERO)
                    .message("快递配送信息不完整，请稍后重试")
                    .build();
        }

        // 1. 店铺是否开通快递配送
        if (shop.getExpressEnabled() == null || shop.getExpressEnabled() != 1) {
            return ExpressCheckVO.builder()
                    .canDeliver(false)
                    .fee(BigDecimal.ZERO)
                    .message("本店暂未开通快递配送，请选择「到店自取」")
                    .build();
        }

        BigDecimal baseFee = shop.getExpressFee() != null ? shop.getExpressFee() : BigDecimal.ZERO;
        List<ShopExpressRegion> regions = shop.getExpressRegions() != null
                ? shop.getExpressRegions()
                : parseExpressRegions(shop.getExpressRegionsJson());

        // 2. 特殊地区优先匹配：按省份/完整地址包含关键字
        String province = address.getProvince();
        String fullAddress = address.getFullAddress();
        for (ShopExpressRegion region : regions) {
            if (region == null || !StringUtils.hasText(region.getRegion())) {
                continue;
            }
            String keyword = region.getRegion().trim();
            boolean matched = (StringUtils.hasText(province) && province.contains(keyword))
                    || (StringUtils.hasText(fullAddress) && fullAddress.contains(keyword));
            if (!matched) {
                continue;
            }
            if (region.getDeliverable() != null && region.getDeliverable() == 0) {
                return ExpressCheckVO.builder()
                        .canDeliver(false)
                        .fee(BigDecimal.ZERO)
                        .matchedRegion(keyword)
                        .message(String.format("「%s」等地区暂停快递发货，请更换收货地址或选择到店自取", keyword))
                        .build();
            }
            BigDecimal fee = region.getFee() != null ? region.getFee() : baseFee;
            return ExpressCheckVO.builder()
                    .canDeliver(true)
                    .fee(fee)
                    .matchedRegion(keyword)
                    .message(String.format("该地址属于快递特殊地区「%s」，运费 ¥%.2f", keyword, fee.doubleValue()))
                    .build();
        }

        // 3. 常规地区：使用店铺基础快递费
        return ExpressCheckVO.builder()
                .canDeliver(true)
                .fee(baseFee)
                .message(String.format("快递运费 ¥%.2f，掌柜接单后安排寄出", baseFee.doubleValue()))
                .build();
    }
}
