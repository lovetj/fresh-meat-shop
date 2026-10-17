package com.freshmeat.controller;

import com.freshmeat.common.Result;
import com.freshmeat.dto.AddressDTO;
import com.freshmeat.entity.UserAddress;
import com.freshmeat.service.UserAddressService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Api(tags = "顾客收货地址模块")
@RestController
@RequestMapping("/address")
@RequiredArgsConstructor
public class UserAddressController {

    private final UserAddressService addressService;

    /**
     * 查询我的地址列表
     */
    @ApiOperation(value = "查询我的地址列表", notes = "获取当前登录用户的所有收货地址")
    @GetMapping("/list")
    public Result<List<UserAddress>> list() {
        return Result.success(addressService.listMyAddresses());
    }

    /**
     * 获取默认地址
     */
    @ApiOperation(value = "获取默认收货地址", notes = "进入下单页时默认加载的收货地址")
    @GetMapping("/default")
    public Result<UserAddress> getDefault() {
        return Result.success(addressService.getDefaultAddress());
    }

    /**
     * 保存或更新地址
     */
    @ApiOperation(value = "保存/更新收货地址", notes = "包含腾讯地图选点后的经纬度，用于同城配送测距")
    @PostMapping("/save")
    public Result<Boolean> saveOrUpdate(@RequestBody @Validated AddressDTO dto) {
        boolean ok = addressService.saveOrUpdateAddress(dto);
        return Result.success("保存地址成功", ok);
    }

    /**
     * 设置默认地址
     */
    @ApiOperation(value = "设为默认地址", notes = "将指定地址设为默认，其余自动取消默认")
    @PostMapping("/default/{id}")
    public Result<Boolean> setDefault(@PathVariable String id) {
        boolean ok = addressService.setDefaultAddress(id);
        return Result.success("已设为默认地址", ok);
    }

    /**
     * 删除地址
     */
    @ApiOperation(value = "删除收货地址", notes = "根据ID删除指定收货地址")
    @DeleteMapping("/{id}")
    public Result<Boolean> delete(@PathVariable String id) {
        boolean ok = addressService.deleteAddress(id);
        return Result.success("删除成功", ok);
    }
}
