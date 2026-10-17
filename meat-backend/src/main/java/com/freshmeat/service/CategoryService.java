package com.freshmeat.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.freshmeat.dto.CategoryDTO;
import com.freshmeat.entity.Category;

import java.util.List;

public interface CategoryService extends IService<Category> {

    /**
     * 获取公开启用的分类列表 (前台顾客端，根据店铺隔离)
     * @param shopId 店铺ID (若为空则取主店铺)
     */
    List<Category> listActiveCategories(String shopId);

    /**
     * 管理端分类列表 (掌柜端，按店铺查询并统计商品数)
     * @param shopId 店铺ID (若为空则返回当前管理员管辖店铺的分类)
     */
    List<Category> listAdminCategories(String shopId);

    /**
     * 新增或编辑分类
     */
    boolean saveOrUpdateCategory(CategoryDTO dto);

    /**
     * 删除分类
     */
    boolean deleteCategory(String id);
}
