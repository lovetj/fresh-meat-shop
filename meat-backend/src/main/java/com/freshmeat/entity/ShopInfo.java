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

    /** 是否开通同城商家配送: 1-开通, 0-未开通 */
    private Integer merchantDeliveryEnabled;

    /** 是否开通外卖配送: 1-开通, 0-未开通（需求待定，暂仅作配置预留） */
    private Integer takeoutEnabled;

    /** 临时计算字段: 与当前定位距离(km)，不入库 */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private Double distance;

    /** 同城配送多阶梯规则列表，不入 shop_info 主表 */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private java.util.List<ShopDeliveryRule> deliveryRules;

    /** 是否开启快递配送: 1-开启, 0-未开通 */
    private Integer expressEnabled;

    /** 快递基础运费(元) */
    private BigDecimal expressFee;

    /** 快递特殊地区规则 JSON 字符串（入库字段 express_regions） */
    @com.baomidou.mybatisplus.annotation.TableField("express_regions")
    private String expressRegionsJson;

    /** 快递特殊地区规则列表（接口输出用，不入库） */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private java.util.List<ShopExpressRegion> expressRegions;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
