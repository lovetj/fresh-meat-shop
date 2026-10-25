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

    /** 到店自取最小备货时间(分钟) */
    private Integer pickupMinMinutes;

    /** 同城商家配送最小送达时间(分钟) */
    private Integer merchantMinMinutes;

    /** 外卖配送最小送达时间(分钟) */
    private Integer takeoutMinMinutes;

    /** 期望时间可选档位间隔(分钟)：15/30/45/60 */
    private Integer timeSlotMinutes;

    /** 可预约天数(含当天)：0-仅当天, 1-当天+次日 ... */
    private Integer reserveDays;

    /** 多阶梯同城配送规则列表 */
    private java.util.List<com.freshmeat.entity.ShopDeliveryRule> deliveryRules;

    /** 是否开启快递配送: 1-开启, 0-未开通 */
    private Integer expressEnabled;

    /** 快递基础运费(元) */
    private BigDecimal expressFee;

    /** 快递特殊地区规则（运费差异 / 停发地区） */
    private java.util.List<com.freshmeat.entity.ShopExpressRegion> expressRegions;

    /* ================= 配送方式 × 支付方式开关（1-开启, 0-关闭，不传则保持原值） ================= */

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

    /** 营业状态: 1-营业中(按每日营业时间自动判定), 2-休息中(手动), 3-歇业中(停止接单)；不传则保持原值 */
    private Integer status;

    private Integer sortOrder;
}
