package com.freshmeat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.freshmeat.dto.AddressDTO;
import com.freshmeat.entity.UserAddress;
import com.freshmeat.exception.BusinessException;
import com.freshmeat.mapper.UserAddressMapper;
import com.freshmeat.service.UserAddressService;
import com.freshmeat.util.SecurityContextUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserAddressServiceImpl extends ServiceImpl<UserAddressMapper, UserAddress> implements UserAddressService {

    @Override
    public List<UserAddress> listMyAddresses() {
        String userId = SecurityContextUtil.getUserId();
        if (userId == null) {
            throw new BusinessException(401, "请先登录");
        }
        return this.list(new LambdaQueryWrapper<UserAddress>()
                .eq(UserAddress::getUserId, userId)
                .orderByDesc(UserAddress::getIsDefault)
                .orderByDesc(UserAddress::getCreateTime));
    }

    @Override
    public UserAddress getDefaultAddress() {
        String userId = SecurityContextUtil.getUserId();
        if (userId == null) {
            throw new BusinessException(401, "请先登录");
        }
        UserAddress defaultAddress = this.getOne(new LambdaQueryWrapper<UserAddress>()
                .eq(UserAddress::getUserId, userId)
                .eq(UserAddress::getIsDefault, 1));
        if (defaultAddress == null) {
            // 取最近使用的一条地址
            defaultAddress = this.list(new LambdaQueryWrapper<UserAddress>()
                    .eq(UserAddress::getUserId, userId)
                    .orderByDesc(UserAddress::getCreateTime)).stream().findFirst().orElse(null);
        }
        return defaultAddress;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean saveOrUpdateAddress(AddressDTO dto) {
        String userId = SecurityContextUtil.getUserId();
        if (userId == null) {
            throw new BusinessException(401, "请先登录");
        }

        // 拼接完整地址
        StringBuilder full = new StringBuilder();
        if (StringUtils.hasText(dto.getProvince())) full.append(dto.getProvince());
        if (StringUtils.hasText(dto.getCity())) full.append(dto.getCity());
        if (StringUtils.hasText(dto.getDistrict())) full.append(dto.getDistrict());
        if (StringUtils.hasText(dto.getAddress())) full.append(dto.getAddress());
        dto.setFullAddress(full.toString());

        // 如果设为默认地址，先将该用户其它地址改为非默认
        if (dto.getIsDefault() != null && dto.getIsDefault() == 1) {
            this.lambdaUpdate()
                    .eq(UserAddress::getUserId, userId)
                    .set(UserAddress::getIsDefault, 0)
                    .update();
        }

        UserAddress address;
        if (dto.getId() != null) {
            address = this.getById(dto.getId());
            if (address == null || !address.getUserId().equals(userId)) {
                throw new BusinessException("地址不存在或无权修改");
            }
            BeanUtils.copyProperties(dto, address);
            return this.updateById(address);
        } else {
            // 如果是用户的第一条地址，自动设为默认
            long count = this.count(new LambdaQueryWrapper<UserAddress>().eq(UserAddress::getUserId, userId));
            if (count == 0) {
                dto.setIsDefault(1);
            }
            address = new UserAddress();
            BeanUtils.copyProperties(dto, address);
            address.setUserId(userId);
            return this.save(address);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean setDefaultAddress(String id) {
        String userId = SecurityContextUtil.getUserId();
        if (userId == null) {
            throw new BusinessException(401, "请先登录");
        }
        UserAddress target = this.getById(id);
        if (target == null || !target.getUserId().equals(userId)) {
            throw new BusinessException("地址不存在");
        }

        this.lambdaUpdate()
                .eq(UserAddress::getUserId, userId)
                .set(UserAddress::getIsDefault, 0)
                .update();

        target.setIsDefault(1);
        return this.updateById(target);
    }

    @Override
    public boolean deleteAddress(String id) {
        String userId = SecurityContextUtil.getUserId();
        if (userId == null) {
            throw new BusinessException(401, "请先登录");
        }
        UserAddress target = this.getById(id);
        if (target == null || !target.getUserId().equals(userId)) {
            throw new BusinessException("地址不存在");
        }
        return this.removeById(id);
    }
}
