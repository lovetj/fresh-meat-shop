package com.freshmeat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("product")
public class Product implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 所属商铺ID */
    private String shopId;

    /** 分类ID (与分类强绑定) */
    private String categoryId;

    /** 所属分类名称 (非持久化字段，关联查询回显) */
    @TableField(exist = false)
    private String categoryName;

    /** 所属分类图标 (非持久化字段，关联查询回显) */
    @TableField(exist = false)
    private String categoryIcon;

    /** 商品名称 */
    private String name;

    /** 商品简述卖点 */
    private String summary;

    /** 商品图文详情 */
    private String description;

    /** 商品主图 */
    private String coverImage;

    /** 当前售价 (元) */
    private BigDecimal price;

    /** 划线原价 (元) */
    private BigDecimal originalPrice;

    /** 计价单位 (斤/盒/份/条) */
    private String unit;

    /** 库存量 */
    private Integer stock;

    /** 已售销量 */
    private Integer sales;

    /** 规格JSON字符串 (如规格项、份量、切法等) */
    private String specsJson;

    /** 是否上架: 1-上架, 0-下架 */
    private Integer isOnSale;

    /** 排序 (越大越前) */
    private Integer sortOrder;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
