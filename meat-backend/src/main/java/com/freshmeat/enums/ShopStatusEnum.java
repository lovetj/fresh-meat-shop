package com.freshmeat.enums;

/**
 * 店铺营业状态（掌柜手动设置的三态）
 *
 * <p>OPEN 营业中：按「每日营业时间」自动判定，时段内展示营业中、时段外自动展示休息中；</p>
 * <p>RESTING 休息中：掌柜主动休息，不受营业时间限制，始终展示休息中；</p>
 * <p>CLOSED 歇业中：相当于关门，禁止顾客下单。</p>
 *
 * <p>注：数据库历史值 0 曾表示「休息中」，读取时统一归一为 RESTING。</p>
 */
public enum ShopStatusEnum {

    /** 营业中：可下单，超过每日营业时间自动降级展示为休息中 */
    OPEN(1, "营业中", 0),

    /** 休息中：掌柜主动休息，不受营业时间限制，仍可下单 */
    RESTING(2, "休息中", 1),

    /** 歇业中：关门停止接单，禁止下单 */
    CLOSED(3, "歇业中", 2);

    private final int code;
    private final String text;
    /** 顾客端列表排序权重：越小越靠前（营业中 > 休息中 > 歇业中） */
    private final int priority;

    ShopStatusEnum(int code, String text, int priority) {
        this.code = code;
        this.text = text;
        this.priority = priority;
    }

    public int getCode() {
        return code;
    }

    public String getText() {
        return text;
    }

    public int getPriority() {
        return priority;
    }

    /** 是否允许顾客下单：仅歇业中禁止 */
    public boolean isAllowOrder() {
        return this != CLOSED;
    }

    /** 状态码归一：兼容历史 0（休息中），非法值回落为营业中 */
    public static ShopStatusEnum of(Integer code) {
        if (code == null) {
            return OPEN;
        }
        switch (code) {
            case 1:
                return OPEN;
            case 2:
            case 0:
                return RESTING;
            case 3:
                return CLOSED;
            default:
                return OPEN;
        }
    }

    public static String textOf(Integer code) {
        return of(code).getText();
    }
}
