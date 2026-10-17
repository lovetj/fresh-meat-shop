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
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("category")
public class Category implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 所属商铺ID (分类与店铺绑定) */
    private String shopId;

    /** 所属商铺名称 (非持久化字段，方便前端回显) */
    @TableField(exist = false)
    private String shopName;

    /** 分类名称 */
    private String name;

    /** 分类图标/示意图 (允许为空) */
    private String icon;

    /** 排序 (越大越前) */
    private Integer sortOrder;

    /** 状态: 1-启用, 0-停用 */
    private Integer status;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;

    /** 关联商品数量 (非持久化字段) */
    @TableField(exist = false)
    private Long productCount;
}
