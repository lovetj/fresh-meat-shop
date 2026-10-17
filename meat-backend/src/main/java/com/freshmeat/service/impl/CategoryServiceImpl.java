package com.freshmeat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.freshmeat.dto.CategoryDTO;
import com.freshmeat.entity.Category;
import com.freshmeat.entity.Product;
import com.freshmeat.entity.ShopInfo;
import com.freshmeat.exception.BusinessException;
import com.freshmeat.mapper.CategoryMapper;
import com.freshmeat.mapper.ProductMapper;
import com.freshmeat.service.CategoryService;
import com.freshmeat.service.ShopInfoService;
import com.freshmeat.util.FileUrlUtil;
import com.freshmeat.util.SecurityContextUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CategoryServiceImpl extends ServiceImpl<CategoryMapper, Category> implements CategoryService {

    private final ProductMapper productMapper;
    private final ShopInfoService shopInfoService;

    @Override
    public List<Category> listActiveCategories(String shopId) {
        String targetShopId = shopId;
        if (!StringUtils.hasText(targetShopId)) {
            ShopInfo defaultShop = shopInfoService.getShopInfo();
            if (defaultShop != null) {
                targetShopId = defaultShop.getId();
            }
        }

        LambdaQueryWrapper<Category> qw = new LambdaQueryWrapper<Category>()
                .eq(Category::getStatus, 1);
        if (StringUtils.hasText(targetShopId)) {
            qw.eq(Category::getShopId, targetShopId);
        }
        qw.orderByDesc(Category::getSortOrder).orderByAsc(Category::getId);
        return this.list(qw);
    }

    @Override
    public List<Category> listAdminCategories(String shopId) {
        String currentUserId = SecurityContextUtil.getUserId();
        LambdaQueryWrapper<Category> qw = new LambdaQueryWrapper<>();

        if (StringUtils.hasText(shopId)) {
            // 核验对该店铺的管理权限
            if (!shopInfoService.hasShopPermission(currentUserId, shopId)) {
                throw new BusinessException(403, "无权管理该店铺的分类");
            }
            qw.eq(Category::getShopId, shopId);
        } else {
            // 未指定店铺，获取该管理员名下所有店铺
            List<String> adminShopIds = shopInfoService.getAdminShopIds(currentUserId);
            if (CollectionUtils.isEmpty(adminShopIds)) {
                return Collections.emptyList();
            }
            qw.in(Category::getShopId, adminShopIds);
        }

        qw.orderByDesc(Category::getSortOrder).orderByAsc(Category::getId);
        List<Category> list = this.list(qw);

        if (!CollectionUtils.isEmpty(list)) {
            // 批量装配店铺名称及绑定的商品数量
            Set<String> shopIds = list.stream().map(Category::getShopId).filter(StringUtils::hasText).collect(Collectors.toSet());
            Map<String, String> shopNameMap = new HashMap<>();
            if (!CollectionUtils.isEmpty(shopIds)) {
                List<ShopInfo> shops = shopInfoService.listByIds(shopIds);
                if (!CollectionUtils.isEmpty(shops)) {
                    shopNameMap = shops.stream().collect(Collectors.toMap(ShopInfo::getId, ShopInfo::getName, (k1, k2) -> k1));
                }
            }

            for (Category cat : list) {
                if (StringUtils.hasText(cat.getShopId())) {
                    cat.setShopName(shopNameMap.get(cat.getShopId()));
                }
                Long count = productMapper.selectCount(new LambdaQueryWrapper<Product>().eq(Product::getCategoryId, cat.getId()));
                cat.setProductCount(count != null ? count : 0L);
            }
        }
        return list;
    }

    @Override
    public boolean saveOrUpdateCategory(CategoryDTO dto) {
        String currentUserId = SecurityContextUtil.getUserId();
        Category category;

        if (dto.getId() != null) {
            category = this.getById(dto.getId());
            if (category == null) {
                throw new BusinessException("待修改分类不存在");
            }
            // 校验对原店铺的权限
            if (!shopInfoService.hasShopPermission(currentUserId, category.getShopId())) {
                throw new BusinessException(403, "无权修改该店铺分类");
            }

            BeanUtils.copyProperties(dto, category);
            // 若前端指定了新的店铺ID，校验新店铺权限
            if (StringUtils.hasText(dto.getShopId())) {
                if (!shopInfoService.hasShopPermission(currentUserId, dto.getShopId())) {
                    throw new BusinessException(403, "无权将分类调整至目标店铺");
                }
                category.setShopId(dto.getShopId());
            }

            if (StringUtils.hasText(category.getIcon())) {
                category.setIcon(FileUrlUtil.toRelative(category.getIcon()));
            } else {
                category.setIcon("");
            }
            return this.updateById(category);
        } else {
            // 新增分类
            String shopId = dto.getShopId();
            if (!StringUtils.hasText(shopId)) {
                List<String> adminShopIds = shopInfoService.getAdminShopIds(currentUserId);
                if (CollectionUtils.isEmpty(adminShopIds)) {
                    throw new BusinessException(403, "未绑定任何店铺，无法创建分类");
                }
                shopId = adminShopIds.get(0);
            } else {
                if (!shopInfoService.hasShopPermission(currentUserId, shopId)) {
                    throw new BusinessException(403, "无权向该店铺添加分类");
                }
            }

            category = new Category();
            BeanUtils.copyProperties(dto, category);
            category.setShopId(shopId);
            if (StringUtils.hasText(category.getIcon())) {
                category.setIcon(FileUrlUtil.toRelative(category.getIcon()));
            } else {
                category.setIcon("");
            }
            return this.save(category);
        }
    }

    @Override
    public boolean deleteCategory(String id) {
        Category category = this.getById(id);
        if (category == null) {
            return false;
        }
        String currentUserId = SecurityContextUtil.getUserId();
        if (!shopInfoService.hasShopPermission(currentUserId, category.getShopId())) {
            throw new BusinessException(403, "无权删除该店铺分类");
        }

        Long count = productMapper.selectCount(new LambdaQueryWrapper<Product>().eq(Product::getCategoryId, id));
        if (count != null && count > 0) {
            throw new BusinessException("该分类下已有商品绑定，无法直接删除");
        }
        return this.removeById(id);
    }
}
