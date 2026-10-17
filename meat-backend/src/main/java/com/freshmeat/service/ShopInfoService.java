package com.freshmeat.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.freshmeat.common.PageResult;
import com.freshmeat.dto.DistanceCheckDTO;
import com.freshmeat.dto.DistanceCheckVO;
import com.freshmeat.dto.ShopInfoDTO;
import com.freshmeat.dto.ShopQueryDTO;
import com.freshmeat.entity.ShopInfo;
import com.freshmeat.entity.User;

import java.math.BigDecimal;
import java.util.List;

public interface ShopInfoService extends IService<ShopInfo> {

    /**
     * 获取商铺信息配置 (若传入 shopId 则获取指定店铺，否则获取默认营业店铺)
     */
    ShopInfo getShopInfo();

    /**
     * 获取指定商铺信息配置
     */
    ShopInfo getShopInfo(String shopId);

    /**
     * 顾客端：获取所有营业商铺列表 (支持根据用户当前经纬度计算直线距离并就近排序)
     */
    List<ShopInfo> listCustomerShops(BigDecimal longitude, BigDecimal latitude);

    /**
     * 顾客端：分页获取商铺列表（支持门店名称/地址模糊搜索、经纬度测距排序）
     */
    PageResult<ShopInfo> pageCustomerShops(ShopQueryDTO queryDTO);

    /**
     * 管理端：获取当前管理员有权管理的所有商铺列表
     */
    List<ShopInfo> listAdminShops();

    /**
     * 管理端：获取某店铺绑定的管理员列表
     */
    List<User> listShopAdmins(String shopId);

    /**
     * 管理端：为店铺绑定新的管理员账号
     */
    boolean bindShopAdmin(String shopId, String username, String roleType);

    /**
     * 管理端：解绑店铺管理员
     */
    boolean unbindShopAdmin(String shopId, String userId);

    /**
     * 检查当前用户对指定商铺是否有管理权限
     */
    boolean hasShopPermission(String userId, String shopId);

    /**
     * 获取当前管理员拥有的所有商铺ID集合
     */
    List<String> getAdminShopIds(String userId);

    /**
     * 更新或新增商铺信息与配送范围配置 (管理员权限)
     */
    boolean updateShopInfo(ShopInfoDTO dto);

    /**
     * 校验收货地址经纬度是否在商铺配送范围之内 (基于 Haversine 算法)
     */
    DistanceCheckVO checkDeliveryDistance(DistanceCheckDTO dto);
}
