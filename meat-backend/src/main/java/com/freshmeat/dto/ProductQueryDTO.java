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

    /** 排序字段: sales-销量, price-价格, 空或其他-按排序值 (顾客端) */
    private String sortBy;

    /** 排序方向: asc-升序, 其他或空-desc-降序 */
    private String sortOrder;

    /** 页码 */
    private Long pageNum = 1L;

    /** 每页条数 */
    private Long pageSize = 20L;
}
