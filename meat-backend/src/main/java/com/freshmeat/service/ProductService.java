package com.freshmeat.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.freshmeat.common.PageResult;
import com.freshmeat.dto.ProductDTO;
import com.freshmeat.dto.ProductQueryDTO;
import com.freshmeat.entity.Product;

public interface ProductService extends IService<Product> {

    /**
     * 顾客端：查询上架商品列表 (分页)
     */
    PageResult<Product> pageCustomerProducts(ProductQueryDTO queryDTO);

    /**
     * 管理端：查询所有商品列表 (支持上下架筛选、关键词搜索)
     */
    PageResult<Product> pageAdminProducts(ProductQueryDTO queryDTO);

    /**
     * 查询商品详情
     */
    Product getProductDetail(String id);

    /**
     * 保存或更新商品
     */
    boolean saveOrUpdateProduct(ProductDTO dto);

    /**
     * 快捷更新商品上下架状态
     */
    boolean updateSaleStatus(String id, Integer isOnSale);

    /**
     * 快捷更新商品库存
     */
    boolean updateStock(String id, Integer stock);

    /**
     * 删除商品
     */
    boolean deleteProduct(String id);
}
