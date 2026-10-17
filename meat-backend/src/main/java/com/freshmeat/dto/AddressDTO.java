package com.freshmeat.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;

@Data
public class AddressDTO {

    private String id;

    @NotBlank(message = "联系人姓名不能为空")
    private String contactName;

    @NotBlank(message = "联系人电话不能为空")
    private String contactPhone;

    private String province;

    private String city;

    private String district;

    @NotBlank(message = "详细地址不能为空")
    private String address;

    private String fullAddress;

    @NotNull(message = "经度不能为空")
    private BigDecimal longitude;

    @NotNull(message = "纬度不能为空")
    private BigDecimal latitude;

    private Integer isDefault = 0;
}
