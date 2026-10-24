package com.freshmeat.dto;

import lombok.Data;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;

@Data
public class ShopInfoDTO {

    private String id;

    @NotBlank(message = "店铺名称不能为空")
    private String name;

    private String notice;

    @NotBlank(message = "联系电话不能为空")
    private String phone;

    private String businessHours;

    @NotBlank(message = "店铺地址不能为空")
    private String address;

    @NotNull(message = "店铺经度不能为空")
    private BigDecimal longitude;

    @NotNull(message = "店铺纬度不能为空")
    private BigDecimal latitude;

    /** 是否开通同城商家配送: 1-开通, 0-未开通 */
    private Integer merchantDeliveryEnabled;

    /** 是否开通外卖配送: 1-开通, 0-未开通 */
    private Integer takeoutEnabled;

    /** 多阶梯同城配送规则列表 */
    private java.util.List<com.freshmeat.entity.ShopDeliveryRule> deliveryRules;

    /** 是否开启快递配送: 1-开启, 0-未开通 */
    private Integer expressEnabled;

    /** 快递基础运费(元) */
    private BigDecimal expressFee;

    /** 快递特殊地区规则（运费差异 / 停发地区） */
    private java.util.List<com.freshmeat.entity.ShopExpressRegion> expressRegions;

    private Integer status;

    private Integer sortOrder;
}
