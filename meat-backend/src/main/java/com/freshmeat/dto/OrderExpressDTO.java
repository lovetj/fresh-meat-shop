package com.freshmeat.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

/**
 * 快递配送发货信息：掌柜接单后手动填写快递公司与快递单号
 */
@Data
public class OrderExpressDTO {

    /** 快递公司名称，如：顺丰速运 / 中通快递 */
    @NotBlank(message = "请填写快递公司")
    @Size(max = 50, message = "快递公司名称最多 50 个字符")
    private String expressCompany;

    /** 快递单号 */
    @NotBlank(message = "请填写快递单号")
    @Size(max = 64, message = "快递单号最多 64 个字符")
    private String expressNo;
}
