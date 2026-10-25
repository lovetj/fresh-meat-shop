package com.freshmeat.util;

import com.freshmeat.dto.DeliveryTimeOptionsVO;
import com.freshmeat.entity.OrderInfo;
import com.freshmeat.entity.ShopInfo;
import org.springframework.util.StringUtils;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 期望送达 / 到店自取时间计算工具
 *
 * 规则：
 * 1. 不同配送方式有不同的「最小准备时间」：到店自取按店铺备货时长，商家配送/外卖按路上耗时；
 * 2. 时间档位按店铺配置的间隔（默认 30 分钟）在每日营业时间内铺开；
 * 3. 最早可选项 = 当前时间 + 该配送方式的最小准备时间；
 * 4. 支持预约未来 N 天（含当天），超出营业时间则无当日档位。
 */
public final class DeliveryTimeUtil {

    /** 默认到店自取最小备货时间(分钟) */
    public static final int DEFAULT_PICKUP_MIN_MINUTES = 30;
    /** 默认商家配送/外卖最小送达时间(分钟) */
    public static final int DEFAULT_DELIVERY_MIN_MINUTES = 45;
    /** 默认档位间隔(分钟) */
    public static final int DEFAULT_SLOT_MINUTES = 30;
    /** 默认可预约天数(含当天) */
    public static final int DEFAULT_RESERVE_DAYS = 1;
    /** 可预约天数上限，避免生成过多档位 */
    public static final int MAX_RESERVE_DAYS = 7;
    /** 最小准备时间上限(分钟) */
    public static final int MAX_MIN_MINUTES = 24 * 60;

    private static final DateTimeFormatter VALUE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter CLOCK_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter MONTH_DAY_FORMATTER = DateTimeFormatter.ofPattern("MM-dd");

    private DeliveryTimeUtil() {
    }

    /** 取该配送方式下店铺配置的最小准备/配送时间 */
    public static int minMinutesOf(ShopInfo shop, Integer deliveryType) {
        int type = deliveryType == null ? OrderInfo.DELIVERY_MERCHANT : deliveryType;
        Integer configured;
        int fallback;
        if (type == OrderInfo.DELIVERY_PICKUP) {
            configured = shop == null ? null : shop.getPickupMinMinutes();
            fallback = DEFAULT_PICKUP_MIN_MINUTES;
        } else if (type == OrderInfo.DELIVERY_TAKEOUT) {
            configured = shop == null ? null : shop.getTakeoutMinMinutes();
            fallback = DEFAULT_DELIVERY_MIN_MINUTES;
        } else {
            configured = shop == null ? null : shop.getMerchantMinMinutes();
            fallback = DEFAULT_DELIVERY_MIN_MINUTES;
        }
        if (configured == null || configured < 0) {
            return fallback;
        }
        return Math.min(configured, MAX_MIN_MINUTES);
    }

    /** 档位间隔归一：非法值回落默认，限制在 5 ~ 180 分钟 */
    public static int resolveSlotMinutes(Integer slotMinutes) {
        if (slotMinutes == null || slotMinutes <= 0) {
            return DEFAULT_SLOT_MINUTES;
        }
        return Math.max(5, Math.min(slotMinutes, 180));
    }

    /** 可预约天数归一：默认 1，限制在 0 ~ 7 */
    public static int resolveReserveDays(Integer reserveDays) {
        if (reserveDays == null || reserveDays < 0) {
            return DEFAULT_RESERVE_DAYS;
        }
        return Math.min(reserveDays, MAX_RESERVE_DAYS);
    }

    /**
     * 构建某配送方式的期望时间可选项
     */
    public static DeliveryTimeOptionsVO buildOptions(ShopInfo shop, Integer deliveryType) {
        LocalDateTime now = LocalDateTime.now().withSecond(0).withNano(0);
        int minMinutes = minMinutesOf(shop, deliveryType);
        int slotMinutes = resolveSlotMinutes(shop == null ? null : shop.getTimeSlotMinutes());
        int reserveDays = resolveReserveDays(shop == null ? null : shop.getReserveDays());

        LocalDate today = now.toLocalDate();
        LocalDateTime earliest = now.plusMinutes(minMinutes);
        String businessHours = shop == null ? null : shop.getBusinessHours();
        int[] range = resolveBusinessRange(businessHours);

        // 以「档位实际所属日期」分组，跨天营业（如 22:00 - 02:00）时不会把次日凌晨错归到前一日
        Map<LocalDate, List<DeliveryTimeOptionsVO.TimeSlot>> grouped = new LinkedHashMap<>();
        Set<LocalDateTime> dedup = new LinkedHashSet<>();
        String earliestLabel = null;

        for (int d = 0; d <= reserveDays; d++) {
            LocalDate baseDate = today.plusDays(d);
            for (int minuteOfDay = range[0]; minuteOfDay <= range[1]; minuteOfDay += slotMinutes) {
                LocalDateTime slotTime = baseDate.atStartOfDay().plusMinutes(minuteOfDay);
                if (slotTime.isBefore(earliest) || !dedup.add(slotTime)) {
                    continue;
                }
                LocalDate slotDate = slotTime.toLocalDate();
                String label = slotTime.format(CLOCK_FORMATTER);
                grouped.computeIfAbsent(slotDate, key -> new ArrayList<>())
                        .add(DeliveryTimeOptionsVO.TimeSlot.builder()
                                .value(slotTime.format(VALUE_FORMATTER))
                                .label(label)
                                .available(Boolean.TRUE)
                                .build());
                if (earliestLabel == null) {
                    earliestLabel = dayLabel(slotDate, today) + " " + label;
                }
            }
        }

        List<DeliveryTimeOptionsVO.DayOptions> days = new ArrayList<>();
        for (Map.Entry<LocalDate, List<DeliveryTimeOptionsVO.TimeSlot>> entry : grouped.entrySet()) {
            LocalDate date = entry.getKey();
            days.add(DeliveryTimeOptionsVO.DayOptions.builder()
                    .date(date.toString())
                    .dayLabel(dayLabel(date, today))
                    .dateLabel(date.format(MONTH_DAY_FORMATTER))
                    .slots(entry.getValue())
                    .build());
        }

        String tip;
        String earliestTime = null;
        if (days.isEmpty()) {
            tip = "当前已超出店铺可接单时段，暂无可预约时间；下单后掌柜会尽快与您联系确认";
        } else {
            LocalDateTime earliestSlot = LocalDateTime.parse(
                    days.get(0).getSlots().get(0).getValue(), VALUE_FORMATTER);
            earliestTime = earliestSlot.format(VALUE_FORMATTER);
            tip = String.format("最快可预约「%s」，该时段按店铺 %d 分钟备货/配送时效计算", earliestLabel, minMinutes);
        }

        return DeliveryTimeOptionsVO.builder()
                .deliveryType(deliveryType)
                .minMinutes(minMinutes)
                .slotMinutes(slotMinutes)
                .reserveDays(reserveDays)
                .earliestTime(earliestTime)
                .earliestLabel(earliestLabel)
                .tip(tip)
                .days(days)
                .build();
    }

    /**
     * 校验并解析顾客提交的期望时间
     *
     * @return null 表示顾客未传（兼容旧版本客户端/无可预约档位场景）
     * @throws IllegalArgumentException 校验不通过，message 可直接提示顾客
     */
    public static LocalDateTime parseAndValidate(String text, ShopInfo shop, Integer deliveryType) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        LocalDateTime parsed = parse(text.trim());
        if (parsed == null) {
            throw new IllegalArgumentException("期望时间格式不正确，请重新选择");
        }

        // 截断到分钟，避免顾客选中最早档位后提交时因秒数进位被误判为过期
        LocalDateTime now = LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        int minMinutes = minMinutesOf(shop, deliveryType);
        LocalDateTime earliest = now.plusMinutes(minMinutes);
        String timeName = deliveryType != null && deliveryType == OrderInfo.DELIVERY_PICKUP ? "自取" : "送达";
        if (parsed.isBefore(earliest)) {
            throw new IllegalArgumentException(String.format(
                    "期望%s时间至少需在 %d 分钟之后（最早 %s），请重新选择",
                    timeName, minMinutes, earliest.format(VALUE_FORMATTER)));
        }

        int reserveDays = resolveReserveDays(shop == null ? null : shop.getReserveDays());
        LocalDateTime latest = now.plusDays(reserveDays + 1L).with(LocalTime.of(23, 59));
        if (parsed.isAfter(latest)) {
            throw new IllegalArgumentException(String.format(
                    "本店最多支持预约 %d 天内的%s时间，请重新选择",
                    reserveDays + 1, timeName));
        }
        return parsed;
    }

    private static LocalDateTime parse(String text) {
        String normalized = text.replace('T', ' ');
        if (normalized.length() == 16) {
            normalized = normalized + ":00";
        }
        try {
            return LocalDateTime.parse(normalized, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    /** 解析营业时间区间为「相对当天 0 点的分钟数」，支持跨天营业（截止时间 <= 起始时间时视为次日） */
    private static int[] resolveBusinessRange(String businessHours) {
        int start = 6 * 60;
        int end = 22 * 60;
        if (StringUtils.hasText(businessHours)) {
            String[] parts = businessHours.split("[-~至]");
            if (parts.length >= 2) {
                Integer s = parseClock(parts[0]);
                Integer e = parseClock(parts[1]);
                if (s != null && e != null) {
                    start = s;
                    end = e <= s ? e + 24 * 60 : e;
                }
            }
        }
        return new int[]{start, end};
    }

    private static Integer parseClock(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        String value = text.trim();
        String[] parts = value.split(":");
        if (parts.length < 2) {
            return null;
        }
        try {
            int hour = Integer.parseInt(parts[0].trim());
            int minute = Integer.parseInt(parts[1].trim());
            if (hour < 0 || hour > 24 || minute < 0 || minute > 59) {
                return null;
            }
            return hour * 60 + minute;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String dayLabel(LocalDate date, LocalDate today) {
        long diff = date.toEpochDay() - today.toEpochDay();
        if (diff == 0) {
            return "今天";
        }
        if (diff == 1) {
            return "明天";
        }
        if (diff == 2) {
            return "后天";
        }
        return date.format(MONTH_DAY_FORMATTER) + " " + weekLabel(date.getDayOfWeek());
    }

    private static String weekLabel(DayOfWeek dayOfWeek) {
        switch (dayOfWeek) {
            case MONDAY:
                return "周一";
            case TUESDAY:
                return "周二";
            case WEDNESDAY:
                return "周三";
            case THURSDAY:
                return "周四";
            case FRIDAY:
                return "周五";
            case SATURDAY:
                return "周六";
            default:
                return "周日";
        }
    }
}
