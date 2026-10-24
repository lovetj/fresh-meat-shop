package com.freshmeat.controller;

import com.freshmeat.annotation.RequireAdmin;
import com.freshmeat.common.PageResult;
import com.freshmeat.common.Result;
import com.freshmeat.dto.DistanceCheckDTO;
import com.freshmeat.dto.DistanceCheckVO;
import com.freshmeat.dto.ExpressCheckDTO;
import com.freshmeat.dto.ExpressCheckVO;
import com.freshmeat.dto.ShopInfoDTO;
import com.freshmeat.dto.ShopQueryDTO;
import com.freshmeat.entity.ShopInfo;
import com.freshmeat.entity.User;
import com.freshmeat.service.ShopInfoService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Api(tags = "多商铺管理与配送测距模块")
@RestController
@RequestMapping("/shop")
@RequiredArgsConstructor
public class ShopInfoController {

    private final ShopInfoService shopInfoService;

    /**
     * 获取商铺公开信息与配送范围配置 (顾客与商家公用，支持传入 shopId)
     */
    @ApiOperation(value = "获取商铺信息与配送范围", notes = "可传 shopId 获取指定商铺，不传则返回默认营业店铺")
    @GetMapping("/info")
    public Result<ShopInfo> getShopInfo(@RequestParam(value = "shopId", required = false) String shopId) {
        return Result.success(shopInfoService.getShopInfo(shopId));
    }

    /**
     * 顾客端：获取所有商铺列表 (支持按当前定位计算距离由近到远排序)
     */
    @ApiOperation(value = "获取所有商铺列表 (顾客端)", notes = "传入经纬度将自动测距并由近到远智能排序")
    @GetMapping("/list")
    public Result<List<ShopInfo>> listShops(
            @RequestParam(value = "longitude", required = false) BigDecimal longitude,
            @RequestParam(value = "latitude", required = false) BigDecimal latitude
    ) {
        return Result.success(shopInfoService.listCustomerShops(longitude, latitude));
    }

    /**
     * 顾客端：分页获取商铺列表 (支持门店名称搜索与定位测距排序)
     */
    @ApiOperation(value = "分页获取商铺列表 (顾客端)", notes = "支持 pageNum/pageSize 分页、keyword 门店名称/地址模糊搜索，传入经纬度自动测距排序")
    @GetMapping("/page")
    public Result<PageResult<ShopInfo>> pageShops(ShopQueryDTO queryDTO) {
        return Result.success(shopInfoService.pageCustomerShops(queryDTO));
    }

    /**
     * 管理端：获取当前管理员有权管理的所有商铺
     */
    @ApiOperation(value = "获取我的商铺列表 (掌柜端)", notes = "根据当前管理员身份，返回其关联管理的所有店铺")
    @RequireAdmin
    @GetMapping("/admin/my-shops")
    public Result<List<ShopInfo>> listMyShops() {
        return Result.success(shopInfoService.listAdminShops());
    }

    /**
     * 管理端：查询店铺下绑定的所有管理员列表
     */
    @ApiOperation(value = "获取店铺管理员列表", notes = "传入 shopId，查看当前店铺的所有管理员")
    @RequireAdmin
    @GetMapping("/admin/admins")
    public Result<List<User>> listShopAdmins(@RequestParam("shopId") String shopId) {
        return Result.success(shopInfoService.listShopAdmins(shopId));
    }

    /**
     * 管理端：为店铺绑定新的管理员账号
     */
    @ApiOperation(value = "店铺绑定管理员", notes = "传入 shopId, username, roleType(OWNER/ADMIN)")
    @RequireAdmin
    @PostMapping("/admin/bind-admin")
    public Result<Boolean> bindAdmin(@RequestBody Map<String, String> body) {
        String shopId = body.get("shopId");
        String username = body.get("username");
        String roleType = body.getOrDefault("roleType", "ADMIN");
        boolean ok = shopInfoService.bindShopAdmin(shopId, username, roleType);
        return Result.success("管理员绑定成功", ok);
    }

    /**
     * 管理端：从店铺解绑管理员
     */
    @ApiOperation(value = "店铺解绑管理员", notes = "传入 shopId, userId")
    @RequireAdmin
    @PostMapping("/admin/unbind-admin")
    public Result<Boolean> unbindAdmin(@RequestBody Map<String, String> body) {
        String shopId = body.get("shopId");
        String userId = body.get("userId");
        boolean ok = shopInfoService.unbindShopAdmin(shopId, userId);
        return Result.success("管理员已解绑", ok);
    }

    /**
     * 更新或新增商铺信息与配送范围 (仅管理员)
     */
    @ApiOperation(value = "更新/新增商铺信息与配送范围 (掌柜)", notes = "传入 id 则修改现有商铺，不传则创建新分店")
    @RequireAdmin
    @PutMapping("/admin/update")
    public Result<Boolean> updateShopInfo(@RequestBody @Validated ShopInfoDTO dto) {
        boolean ok = shopInfoService.updateShopInfo(dto);
        return Result.success("商铺配置保存成功", ok);
    }

    /**
     * 校验收货地址是否在指定店铺配送范围之内
     */
    @ApiOperation(value = "收货地址配送测距校验", notes = "支持传入 shopId 测算对应分店的距离与起送费")
    @PostMapping("/check-distance")
    public Result<DistanceCheckVO> checkDistance(@RequestBody @Validated DistanceCheckDTO dto) {
        DistanceCheckVO result = shopInfoService.checkDeliveryDistance(dto);
        return Result.success(result);
    }

    /**
     * 顾客端：快递配送运费试算
     * 依据店铺快递开关、基础运费与特殊地区配置，返回是否可发货及运费
     */
    @ApiOperation(value = "快递配送运费试算", notes = "特殊地区可单独设置运费或停发；未开通快递配送时返回不可配送及原因")
    @PostMapping("/express-check")
    public Result<ExpressCheckVO> checkExpress(@RequestBody @Validated ExpressCheckDTO dto) {
        return Result.success(shopInfoService.checkExpressDelivery(dto.getShopId(), dto.getAddressId()));
    }
}
