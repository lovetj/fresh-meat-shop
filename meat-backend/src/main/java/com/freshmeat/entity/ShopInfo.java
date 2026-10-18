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
@TableName("shop_info")
public class ShopInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 店铺名称 */
    private String name;

    /** 店铺公告 */
    private String notice;

    /** 联系电话 */
    private String phone;

    /** 营业时间 (如: 06:00 - 19:30) */
    private String businessHours;

    /** 详细地址 */
    private String address;

    /** 店铺经度 */
    private BigDecimal longitude;

    /** 店铺纬度 */
    private BigDecimal latitude;

    /** 营业状态: 1-营业中, 0-休息中 */
    private Integer status;

    /** 排序权重(越大越靠前) */
    private Integer sortOrder;

    /** 临时计算字段: 与当前定位距离(km)，不入库 */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private Double distance;

    /** 同城配送多阶梯规则列表，不入 shop_info 主表 */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private java.util.List<ShopDeliveryRule> deliveryRules;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
