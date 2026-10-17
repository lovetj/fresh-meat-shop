package com.freshmeat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.freshmeat.common.PageResult;
import com.freshmeat.dto.ProductDTO;
import com.freshmeat.dto.ProductQueryDTO;
import com.freshmeat.entity.Category;
import com.freshmeat.entity.Product;
import com.freshmeat.entity.ShopInfo;
import com.freshmeat.exception.BusinessException;
import com.freshmeat.mapper.CategoryMapper;
import com.freshmeat.mapper.ProductMapper;
import com.freshmeat.service.ProductService;
import com.freshmeat.service.ShopInfoService;
import com.freshmeat.util.FileUrlUtil;
import com.freshmeat.util.SecurityContextUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProductServiceImpl extends ServiceImpl<ProductMapper, Product> implements ProductService {

    private final ShopInfoService shopInfoService;
    private final CategoryMapper categoryMapper;

    @Override
    public PageResult<Product> pageCustomerProducts(ProductQueryDTO queryDTO) {
        Page<Product> page = new Page<>(queryDTO.getPageNum(), queryDTO.getPageSize());
        LambdaQueryWrapper<Product> qw = new LambdaQueryWrapper<Product>()
                .eq(Product::getIsOnSale, 1);

        // 确定所属店铺ID：优先使用传入的 shopId，若未传则默认取主营业店铺
        String targetShopId = queryDTO.getShopId();
        if (!StringUtils.hasText(targetShopId)) {
            ShopInfo defaultShop = shopInfoService.getShopInfo();
            if (defaultShop != null) {
                targetShopId = defaultShop.getId();
            }
        }
        if (StringUtils.hasText(targetShopId)) {
            qw.eq(Product::getShopId, targetShopId);
        }

        if (StringUtils.hasText(queryDTO.getCategoryId())) {
            qw.eq(Product::getCategoryId, queryDTO.getCategoryId());
        }
        if (StringUtils.hasText(queryDTO.getKeyword())) {
            qw.and(wrapper -> wrapper.like(Product::getName, queryDTO.getKeyword())
                    .or().like(Product::getSummary, queryDTO.getKeyword()));
        }

        qw.orderByDesc(Product::getSortOrder).orderByDesc(Product::getCreateTime);
        Page<Product> resultPage = this.page(page, qw);
        fillCategoryInfo(resultPage.getRecords());
        return PageResult.of(resultPage.getTotal(), resultPage.getRecords(), resultPage.getCurrent(), resultPage.getSize());
    }

    @Override
    public PageResult<Product> pageAdminProducts(ProductQueryDTO queryDTO) {
        String currentUserId = SecurityContextUtil.getUserId();
        Page<Product> page = new Page<>(queryDTO.getPageNum(), queryDTO.getPageSize());
        LambdaQueryWrapper<Product> qw = new LambdaQueryWrapper<>();

        if (StringUtils.hasText(queryDTO.getShopId())) {
            // 指定店铺查询，核验权限
            if (!shopInfoService.hasShopPermission(currentUserId, queryDTO.getShopId())) {
                throw new BusinessException(403, "无权管理该店铺商品");
            }
            qw.eq(Product::getShopId, queryDTO.getShopId());
        } else {
            // 未指定店铺，查询当前管理员名下的所有店铺商品
            List<String> adminShopIds = shopInfoService.getAdminShopIds(currentUserId);
            if (CollectionUtils.isEmpty(adminShopIds)) {
                return PageResult.of(0L, Collections.emptyList(), queryDTO.getPageNum(), queryDTO.getPageSize());
            }
            qw.in(Product::getShopId, adminShopIds);
        }

        if (StringUtils.hasText(queryDTO.getCategoryId())) {
            qw.eq(Product::getCategoryId, queryDTO.getCategoryId());
        }
        if (queryDTO.getIsOnSale() != null) {
            qw.eq(Product::getIsOnSale, queryDTO.getIsOnSale());
        }
        if (StringUtils.hasText(queryDTO.getKeyword())) {
            qw.and(wrapper -> wrapper.like(Product::getName, queryDTO.getKeyword())
                    .or().like(Product::getSummary, queryDTO.getKeyword()));
        }

        qw.orderByDesc(Product::getSortOrder).orderByDesc(Product::getCreateTime);
        Page<Product> resultPage = this.page(page, qw);
        fillCategoryInfo(resultPage.getRecords());
        return PageResult.of(resultPage.getTotal(), resultPage.getRecords(), resultPage.getCurrent(), resultPage.getSize());
    }

    @Override
    public Product getProductDetail(String id) {
        Product product = this.getById(id);
        if (product == null) {
            throw new BusinessException("商品不存在或已下架");
        }
        if (StringUtils.hasText(product.getCategoryId())) {
            Category category = categoryMapper.selectById(product.getCategoryId());
            if (category != null) {
                product.setCategoryName(category.getName());
                product.setCategoryIcon(category.getIcon());
            }
        }
        return product;
    }

    @Override
    public boolean saveOrUpdateProduct(ProductDTO dto) {
        String currentUserId = SecurityContextUtil.getUserId();

        // 强校验：商品必须绑定所属分类
        if (!StringUtils.hasText(dto.getCategoryId())) {
            throw new BusinessException("商品必须绑定所属分类");
        }
        Category category = categoryMapper.selectById(dto.getCategoryId());
        if (category == null) {
            throw new BusinessException("所选分类不存在，请重新选择分类");
        }

        Product product;
        if (dto.getId() != null) {
            product = this.getById(dto.getId());
            if (product == null) {
                throw new BusinessException("待修改商品不存在");
            }
            // 校验权限
            if (!shopInfoService.hasShopPermission(currentUserId, product.getShopId())) {
                throw new BusinessException(403, "无权修改该店铺商品");
            }

            String targetShopId = StringUtils.hasText(dto.getShopId()) ? dto.getShopId() : product.getShopId();
            if (StringUtils.hasText(category.getShopId()) && !category.getShopId().equals(targetShopId)) {
                throw new BusinessException("所选分类不属于该店铺，请选择该店铺对应的分类");
            }

            BeanUtils.copyProperties(dto, product);
            // 若前端传了新的 shopId，则校验新店铺权限
            if (StringUtils.hasText(dto.getShopId())) {
                if (!shopInfoService.hasShopPermission(currentUserId, dto.getShopId())) {
                    throw new BusinessException(403, "无权将商品调整至目标店铺");
                }
                product.setShopId(dto.getShopId());
            }

            // 确保入库图片只存相对路径，剥离 baseServer
            if (StringUtils.hasText(product.getCoverImage())) {
                product.setCoverImage(FileUrlUtil.toRelative(product.getCoverImage()));
            }
            return this.updateById(product);
        } else {
            // 新增商品
            String shopId = dto.getShopId();
            if (!StringUtils.hasText(shopId)) {
                // 默认取管理员首个有权限的店铺
                List<String> adminShopIds = shopInfoService.getAdminShopIds(currentUserId);
                if (CollectionUtils.isEmpty(adminShopIds)) {
                    throw new BusinessException(403, "未绑定任何店铺，无法发布商品");
                }
                shopId = adminShopIds.get(0);
            } else {
                if (!shopInfoService.hasShopPermission(currentUserId, shopId)) {
                    throw new BusinessException(403, "无权向该店铺添加商品");
                }
            }

            if (StringUtils.hasText(category.getShopId()) && !category.getShopId().equals(shopId)) {
                throw new BusinessException("所选分类不属于该店铺，请选择该店铺对应的分类");
            }

            product = new Product();
            BeanUtils.copyProperties(dto, product);
            product.setShopId(shopId);
            product.setSales(0);
            if (StringUtils.hasText(product.getCoverImage())) {
                product.setCoverImage(FileUrlUtil.toRelative(product.getCoverImage()));
            }
            return this.save(product);
        }
    }

    @Override
    public boolean updateSaleStatus(String id, Integer isOnSale) {
        Product product = this.getById(id);
        if (product == null) {
            throw new BusinessException("商品不存在");
        }
        String currentUserId = SecurityContextUtil.getUserId();
        if (!shopInfoService.hasShopPermission(currentUserId, product.getShopId())) {
            throw new BusinessException(403, "无权修改该商品状态");
        }
        product.setIsOnSale(isOnSale != null && isOnSale == 1 ? 1 : 0);
        return this.updateById(product);
    }

    @Override
    public boolean updateStock(String id, Integer stock) {
        Product product = this.getById(id);
        if (product == null) {
            throw new BusinessException("商品不存在");
        }
        String currentUserId = SecurityContextUtil.getUserId();
        if (!shopInfoService.hasShopPermission(currentUserId, product.getShopId())) {
            throw new BusinessException(403, "无权修改该商品库存");
        }
        product.setStock(Math.max(0, stock));
        return this.updateById(product);
    }

    @Override
    public boolean deleteProduct(String id) {
        Product product = this.getById(id);
        if (product == null) {
            return false;
        }
        String currentUserId = SecurityContextUtil.getUserId();
        if (!shopInfoService.hasShopPermission(currentUserId, product.getShopId())) {
            throw new BusinessException(403, "无权删除该商品");
        }
        return this.removeById(id);
    }

    /**
     * 批量填充商品绑定的分类信息 (分类名称、图标)
     */
    private void fillCategoryInfo(List<Product> products) {
        if (CollectionUtils.isEmpty(products)) {
            return;
        }
        Set<String> catIds = products.stream()
                .map(Product::getCategoryId)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());
        if (CollectionUtils.isEmpty(catIds)) {
            return;
        }
        List<Category> categories = categoryMapper.selectBatchIds(catIds);
        if (CollectionUtils.isEmpty(categories)) {
            return;
        }
        Map<String, Category> catMap = categories.stream()
                .collect(Collectors.toMap(Category::getId, c -> c, (k1, k2) -> k1));
        for (Product p : products) {
            if (StringUtils.hasText(p.getCategoryId())) {
                Category c = catMap.get(p.getCategoryId());
                if (c != null) {
                    p.setCategoryName(c.getName());
                    p.setCategoryIcon(c.getIcon());
                }
            }
        }
    }
}
