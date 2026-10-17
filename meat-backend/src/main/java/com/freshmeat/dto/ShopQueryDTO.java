package com.freshmeat.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 顾客端门店列表查询条件（分页 + 名称搜索 + 测距排序）
 */
@Data
public class ShopQueryDTO {

    /** 搜索关键词：匹配门店名称或地址 */
    private String keyword;

    /** 用户当前经度（传入则返回距离并按由近到远排序） */
    private BigDecimal longitude;

    /** 用户当前纬度 */
    private BigDecimal latitude;

    /** 页码 */
    private Long pageNum = 1L;

    /** 每页条数 */
    private Long pageSize = 10L;
}
