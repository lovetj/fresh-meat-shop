package com.freshmeat.dto;

import lombok.Data;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;

@Data
public class ProductDTO {

    private String id;

    private String shopId;

    @NotBlank(message = "所属分类不能为空")
    private String categoryId;

    @NotBlank(message = "商品名称不能为空")
    private String name;

    private String summary;

    private String description;

    @NotBlank(message = "商品图片不能为空")
    private String coverImage;

    @NotNull(message = "商品价格不能为空")
    @DecimalMin(value = "0.01", message = "商品价格必须大于0")
    private BigDecimal price;

    private BigDecimal originalPrice;

    private String unit = "斤";

    private Integer stock = 999;

    private String specsJson;

    private Integer isOnSale = 1;

    private Integer sortOrder = 0;
}
