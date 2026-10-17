package com.freshmeat.dto;

import lombok.Data;
import javax.validation.constraints.NotBlank;

@Data
public class CategoryDTO {

    private String id;

    /** 所属商铺ID (若不传默认归属主店铺或当前管理员管理的首个店铺) */
    private String shopId;

    @NotBlank(message = "分类名称不能为空")
    private String name;

    /** 分类图标 (允许为空) */
    private String icon;

    private Integer sortOrder = 0;

    private Integer status = 1;
}
