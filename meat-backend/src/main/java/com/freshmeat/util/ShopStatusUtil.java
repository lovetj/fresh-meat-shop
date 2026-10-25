package com.freshmeat.util;

import com.freshmeat.entity.ShopInfo;
import com.freshmeat.enums.ShopStatusEnum;
import com.freshmeat.exception.BusinessException;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 店铺营业状态计算工具
 *
 * <p>掌柜手动设置的 status 只是「意愿状态」，顾客端展示的「实际状态」还需要叠加每日营业时间判定：</p>
 * <ul>
 *   <li>歇业中：直接歇业，禁止下单；</li>
 *   <li>休息中：掌柜主动休息，不做时间限制，始终休息中；</li>
 *   <li>营业中：当前时间在「每日营业时间」内才是营业中，否则自动降级为休息中。</li>
 * </ul>
 */
public final class ShopStatusUtil {

    /** 时间段解析：支持 "06:00 - 19:30"、"6:00~19:30" 等写法 */
    private static final Pattern TIME_PATTERN = Pattern.compile("(\\d{1,2})\\s*[:：]\\s*(\\d{1,2})");

    /** 多个营业时段的分隔符，如 "06:00-11:00, 15:00-19:00" */
    private static final String RANGE_SEPARATOR_REGEX = "[,，;；、/]";

    private ShopStatusUtil() {
    }

    /** 计算店铺的实际展示状态（按服务器当前时间） */
    public static ShopStatusEnum resolve(ShopInfo shop) {
        return resolve(shop, LocalTime.now());
    }

    /** 计算店铺的实际展示状态（指定时间，便于测试） */
    public static ShopStatusEnum resolve(ShopInfo shop, LocalTime now) {
        if (shop == null) {
            return ShopStatusEnum.OPEN;
        }
        ShopStatusEnum manual = ShopStatusEnum.of(shop.getStatus());
        // 歇业中 / 手动休息中：不受营业时间影响
        if (manual != ShopStatusEnum.OPEN) {
            return manual;
        }
        return isWithinBusinessHours(shop.getBusinessHours(), now) ? ShopStatusEnum.OPEN : ShopStatusEnum.RESTING;
    }

    /** 顾客端门店排序权重：营业中 < 休息中 < 歇业中 */
    public static int priorityOf(ShopInfo shop) {
        return resolve(shop).getPriority();
    }

    /** 是否禁止下单：仅歇业中禁止，休息中仍可下单 */
    public static boolean isOrderBlocked(ShopInfo shop) {
        return resolve(shop) == ShopStatusEnum.CLOSED;
    }

    /** 歇业拦截文案，供下单校验与可下单性查询复用 */
    public static String orderBlockedMessage(ShopInfo shop) {
        String shopName = shop == null || shop.getName() == null ? "该门店" : "「" + shop.getName() + "」";
        return shopName + "当前已歇业，暂停接单，请稍后再来～";
    }

    /**
     * 校验门店是否允许下单：歇业中直接抛业务异常（下单接口统一入口）
     */
    public static void assertAllowOrder(ShopInfo shop) {
        if (shop == null) {
            throw new BusinessException("下单店铺不存在");
        }
        if (isOrderBlocked(shop)) {
            throw new BusinessException(orderBlockedMessage(shop));
        }
    }

    /** 把实际展示状态、下单开关等临时字段写入店铺对象（不入库） */
    public static void fill(ShopInfo shop) {
        fill(shop, LocalTime.now());
    }

    public static void fill(ShopInfo shop, LocalTime now) {
        if (shop == null) {
            return;
        }
        LocalTime currentTime = now != null ? now : LocalTime.now();
        ShopStatusEnum display = resolve(shop, currentTime);
        shop.setDisplayStatus(display.getCode());
        shop.setStatusText(display.getText());
        shop.setAllowOrder(display.isAllowOrder());
        shop.setInBusinessHours(isWithinBusinessHours(shop.getBusinessHours(), currentTime));
    }

    /**
     * 当前时间是否在每日营业时段内
     *
     * <p>支持跨天营业（如 22:00 - 02:00）；营业时间为空或格式无法识别时不做时间限制，返回 true。</p>
     */
    public static boolean isWithinBusinessHours(String businessHours, LocalTime now) {
        if (businessHours == null || businessHours.trim().isEmpty()) {
            return true;
        }
        LocalTime currentTime = now != null ? now : LocalTime.now();

        boolean parsed = false;
        for (String segment : businessHours.split(RANGE_SEPARATOR_REGEX)) {
            List<LocalTime> times = parseTimes(segment);
            if (times.size() < 2) {
                continue;
            }
            parsed = true;
            if (contains(times.get(0), times.get(1), currentTime)) {
                return true;
            }
        }
        // 一个时段都解析不出来时视为「全天营业」，避免误把门店判定为休息中
        return !parsed;
    }

    /** 解析时间段内的起止时间，按出现顺序返回 */
    private static List<LocalTime> parseTimes(String segment) {
        List<LocalTime> times = new ArrayList<>(2);
        if (segment == null) {
            return times;
        }
        Matcher matcher = TIME_PATTERN.matcher(segment);
        while (matcher.find() && times.size() < 2) {
            int hour = Integer.parseInt(matcher.group(1));
            int minute = Integer.parseInt(matcher.group(2));
            if (hour > 23 || minute > 59) {
                continue;
            }
            times.add(LocalTime.of(hour, minute));
        }
        return times;
    }

    /** 判断时间点是否落在 [start, end) 内，支持跨天 */
    private static boolean contains(LocalTime start, LocalTime end, LocalTime now) {
        if (start.equals(end)) {
            // 起止相同视为 24 小时营业
            return true;
        }
        if (start.isBefore(end)) {
            return !now.isBefore(start) && now.isBefore(end);
        }
        // 跨天：如 22:00 - 02:00
        return !now.isBefore(start) || now.isBefore(end);
    }
}
