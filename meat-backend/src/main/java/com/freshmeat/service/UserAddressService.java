package com.freshmeat.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.freshmeat.dto.AddressDTO;
import com.freshmeat.entity.UserAddress;

import java.util.List;

public interface UserAddressService extends IService<UserAddress> {

    /**
     * 获取当前用户的收货地址列表
     */
    List<UserAddress> listMyAddresses();

    /**
     * 获取当前用户的默认收货地址
     */
    UserAddress getDefaultAddress();

    /**
     * 保存或更新收货地址
     */
    boolean saveOrUpdateAddress(AddressDTO dto);

    /**
     * 设置默认收货地址
     */
    boolean setDefaultAddress(String id);

    /**
     * 删除收货地址
     */
    boolean deleteAddress(String id);
}
