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

    /**
     * 营业状态(掌柜手动设置): 1-营业中(按每日营业时间自动判定), 2-休息中(手动，不受时间限制), 3-歇业中(停止接单)
     */
    private Integer status;

    /** 实际展示状态(不入库): 1-营业中, 2-休息中, 3-歇业中 */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private Integer displayStatus;

    /** 实际展示状态文案(不入库): 营业中 / 休息中 / 歇业中 */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private String statusText;

    /** 当前是否允许下单(不入库): 仅歇业中为 false */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private Boolean allowOrder;

    /** 当前是否处于每日营业时间段内(不入库) */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private Boolean inBusinessHours;

    /** 排序权重(越大越靠前) */
    private Integer sortOrder;

    /** 是否开通同城商家配送: 1-开通, 0-未开通 */
    private Integer merchantDeliveryEnabled;

    /** 到店自取最小备货时间(分钟)，顾客可选期望自取时间须晚于「当前时间 + 该值」 */
    private Integer pickupMinMinutes;

    /** 同城商家配送最小送达时间(分钟) */
    private Integer merchantMinMinutes;

    /** 外卖配送最小送达时间(分钟) */
    private Integer takeoutMinMinutes;

    /** 期望时间可选档位间隔(分钟)，如 30 表示 10:00 / 10:30 / 11:00 ... */
    private Integer timeSlotMinutes;

    /** 可预约天数(含当天)：0-仅当天可预约, 1-当天+次日 */
    private Integer reserveDays;

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

    /* ================= 配送方式 × 支付方式开关（1-开启, 0-关闭） ================= */

    /** 到店自取-微信线上支付 */
    private Integer pickupWxpayEnabled;

    /** 到店自取-货到付款 */
    private Integer pickupCodEnabled;

    /** 同城商家配送-微信线上支付 */
    private Integer merchantWxpayEnabled;

    /** 同城商家配送-货到付款 */
    private Integer merchantCodEnabled;

    /** 外卖配送-微信线上支付 */
    private Integer takeoutWxpayEnabled;

    /** 外卖配送-货到付款 */
    private Integer takeoutCodEnabled;

    /** 快递配送-微信线上支付 */
    private Integer expressWxpayEnabled;

    /** 快递配送-货到付款 */
    private Integer expressCodEnabled;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;

    /**
     * 指定配送方式是否开启「微信线上支付」
     *
     * @param deliveryType 1-到店自取, 2-同城商家配送, 3-外卖配送, 4-快递配送
     * @return 1-开启, 0-关闭（未知配送方式视为关闭）
     */
    public int wxpayEnabledOf(Integer deliveryType) {
        return isOn(wxpaySwitchOf(deliveryType));
    }

    /**
     * 指定配送方式是否开启「货到付款」
     *
     * @param deliveryType 1-到店自取, 2-同城商家配送, 3-外卖配送, 4-快递配送
     * @return 1-开启, 0-关闭（未知配送方式视为关闭）
     */
    public int codEnabledOf(Integer deliveryType) {
        return isOn(codSwitchOf(deliveryType));
    }

    private Integer wxpaySwitchOf(Integer deliveryType) {
        if (deliveryType == null) {
            return 0;
        }
        switch (deliveryType) {
            case OrderInfo.DELIVERY_PICKUP:
                return pickupWxpayEnabled;
            case OrderInfo.DELIVERY_MERCHANT:
                return merchantWxpayEnabled;
            case OrderInfo.DELIVERY_TAKEOUT:
                return takeoutWxpayEnabled;
            case OrderInfo.DELIVERY_EXPRESS:
                return expressWxpayEnabled;
            default:
                return 0;
        }
    }

    private Integer codSwitchOf(Integer deliveryType) {
        if (deliveryType == null) {
            return 0;
        }
        switch (deliveryType) {
            case OrderInfo.DELIVERY_PICKUP:
                return pickupCodEnabled;
            case OrderInfo.DELIVERY_MERCHANT:
                return merchantCodEnabled;
            case OrderInfo.DELIVERY_TAKEOUT:
                return takeoutCodEnabled;
            case OrderInfo.DELIVERY_EXPRESS:
                return expressCodEnabled;
            default:
                return 0;
        }
    }

    private int isOn(Integer value) {
        return value != null && value == 1 ? 1 : 0;
    }
}
