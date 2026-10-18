package com.freshmeat.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;

@Data
@ApiModel("掌柜改价请求DTO")
public class OrderPriceChangeDTO {

    @ApiModelProperty(value = "调整后的实付金额(元)", required = true)
    @NotNull(message = "新实付金额不能为空")
    @DecimalMin(value = "0.01", message = "实付金额至少为0.01元")
    private BigDecimal newPayAmount;

    @ApiModelProperty(value = "改价原因(如实际称重出入)")
    private String reason;
}
