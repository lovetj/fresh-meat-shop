package com.freshmeat.dto;

import lombok.Data;

@Data
public class ProductQueryDTO {

    /** 所属商铺ID */
    private String shopId;

    /** 分类ID */
    private String categoryId;

    /** 搜索关键词 */
    private String keyword;

    /** 是否上架: 1-上架, 0-下架 (管理端可查全部或下架商品) */
    private Integer isOnSale;

    /** 页码 */
    private Long pageNum = 1L;

    /** 每页条数 */
    private Long pageSize = 20L;
}
