package com.freshmeat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.freshmeat.common.PageResult;
import com.freshmeat.dto.DistanceCheckDTO;
import com.freshmeat.dto.DistanceCheckVO;
import com.freshmeat.dto.ShopInfoDTO;
import com.freshmeat.dto.ShopQueryDTO;
import com.freshmeat.entity.ShopAdmin;
import com.freshmeat.entity.ShopInfo;
import com.freshmeat.entity.User;
import com.freshmeat.exception.BusinessException;
import com.freshmeat.mapper.ShopAdminMapper;
import com.freshmeat.mapper.ShopInfoMapper;
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

    @Override
    public ShopInfo getShopInfo() {
        return getShopInfo(null);
    }

    @Override
    public ShopInfo getShopInfo(String shopId) {
        if (StringUtils.hasText(shopId)) {
            ShopInfo shop = this.getById(shopId);
            if (shop != null) {
                return shop;
            }
        }

        // 默认策略：优先选择正在营业且排序最高的店铺
        List<ShopInfo> shops = this.list(new LambdaQueryWrapper<ShopInfo>()
                .orderByDesc(ShopInfo::getStatus)
                .orderByDesc(ShopInfo::getSortOrder)
                .orderByAsc(ShopInfo::getCreateTime));

        if (!CollectionUtils.isEmpty(shops)) {
            return shops.get(0);
        }

        // 若库中完全无数据，提供兜底创建
        ShopInfo defaultShop = ShopInfo.builder()
                .id("s_shop_000000000000000000000000001")
                .name("维琼鲜肉铺(科技园旗舰店)")
                .notice("每日清晨现宰直达，同城专人极速送达！")
                .phone("13800138000")
                .businessHours("06:30 - 20:00")
                .address("深圳市南山区科兴科学园A01")
                .longitude(new BigDecimal("113.9435000"))
                .latitude(new BigDecimal("22.5492000"))
                .maxDeliveryDistance(new BigDecimal("5.00"))
                .minOrderAmount(new BigDecimal("15.00"))
                .deliveryFee(new BigDecimal("3.00"))
                .status(1)
                .sortOrder(100)
                .build();
        this.save(defaultShop);
        return defaultShop;
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

        // 超级管理员账号拥有所有店铺权限
        if ("admin".equalsIgnoreCase(user.getUsername())) {
            return this.list(new LambdaQueryWrapper<ShopInfo>()
                    .orderByDesc(ShopInfo::getSortOrder)
                    .orderByAsc(ShopInfo::getCreateTime));
        }

        // 普通管理员按关联表查询
        List<ShopAdmin> relations = shopAdminMapper.selectList(
                new LambdaQueryWrapper<ShopAdmin>().eq(ShopAdmin::getUserId, userId)
        );

        if (CollectionUtils.isEmpty(relations)) {
            // 兼容容错：若未配置特定关联，则默认赋予首个店铺权限
            ShopInfo defaultShop = getShopInfo();
            if (defaultShop != null) {
                ShopAdmin autoRel = ShopAdmin.builder()
                        .shopId(defaultShop.getId())
                        .userId(userId)
                        .roleType("ADMIN")
                        .createTime(LocalDateTime.now())
                        .build();
                shopAdminMapper.insert(autoRel);
                return Collections.singletonList(defaultShop);
            }
            return Collections.emptyList();
        }

        List<String> shopIds = relations.stream().map(ShopAdmin::getShopId).distinct().collect(Collectors.toList());
        return this.listByIds(shopIds);
    }

    @Override
    public List<User> listShopAdmins(String shopId) {
        if (!StringUtils.hasText(shopId)) {
            return Collections.emptyList();
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
        // 超级管理员放行
        if ("admin".equalsIgnoreCase(user.getUsername())) {
            return true;
        }
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
        if (user == null) {
            return Collections.emptyList();
        }
        if ("admin".equalsIgnoreCase(user.getUsername())) {
            return this.list().stream().map(ShopInfo::getId).collect(Collectors.toList());
        }
        List<ShopAdmin> list = shopAdminMapper.selectList(new LambdaQueryWrapper<ShopAdmin>().eq(ShopAdmin::getUserId, userId));
        return list.stream().map(ShopAdmin::getShopId).distinct().collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateShopInfo(ShopInfoDTO dto) {
        String currentUserId = SecurityContextUtil.getUserId();
        if (StringUtils.hasText(dto.getId())) {
            // 权限检查
            if (!hasShopPermission(currentUserId, dto.getId())) {
                throw new BusinessException(403, "无权管理该店铺");
            }
            ShopInfo exist = this.getById(dto.getId());
            if (exist == null) {
                throw new BusinessException("待修改商铺不存在");
            }
            BeanUtils.copyProperties(dto, exist);
            return this.updateById(exist);
        } else {
            // 新增店铺
            ShopInfo newShop = new ShopInfo();
            BeanUtils.copyProperties(dto, newShop);
            if (newShop.getSortOrder() == null) {
                newShop.setSortOrder(0);
            }
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
            return saved;
        }
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
        double maxDist = shop.getMaxDeliveryDistance() != null ? shop.getMaxDeliveryDistance().doubleValue() : 5.0;

        // 计算球面距离 (km)
        double actualDistance = DistanceUtil.calculateDistance(shopLng, shopLat, userLng, userLat);
        boolean canDeliver = actualDistance <= maxDist;

        String message;
        if (canDeliver) {
            message = String.format("距「%s」约 %.2f 公里，在商家配送范围内", shop.getName(), actualDistance);
        } else {
            message = String.format("距「%s」约 %.2f 公里，超出最大配送范围(%.1f公里)，建议选择「自取」或切换至更近的分店",
                    shop.getName(), actualDistance, maxDist);
        }

        return DistanceCheckVO.builder()
                .distance(actualDistance)
                .maxDeliveryDistance(maxDist)
                .canDeliver(canDeliver)
                .deliveryFee(shop.getDeliveryFee() != null ? shop.getDeliveryFee() : BigDecimal.ZERO)
                .minOrderAmount(shop.getMinOrderAmount() != null ? shop.getMinOrderAmount() : BigDecimal.ZERO)
                .message(message)
                .build();
    }
}
