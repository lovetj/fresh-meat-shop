package com.freshmeat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
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
@TableName("user_address")
public class UserAddress implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 所属用户ID */
    private String userId;

    /** 收货人姓名 */
    private String contactName;

    /** 收货人手机号 */
    private String contactPhone;

    /** 省份 */
    private String province;

    /** 城市 */
    private String city;

    /** 区县 */
    private String district;

    /** 详细街道小区门牌号 */
    private String address;

    /** 完整地址拼接 */
    private String fullAddress;

    /** 地址经度 */
    private BigDecimal longitude;

    /** 地址纬度 */
    private BigDecimal latitude;

    /** 是否默认: 1-是, 0-否 */
    private Integer isDefault;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
