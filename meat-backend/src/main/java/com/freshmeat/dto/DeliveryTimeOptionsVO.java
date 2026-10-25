package com.freshmeat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 顾客端「期望送达 / 期望到店自取」时间可选项
 * 由店铺配置（最小备货/配送分钟数、档位间隔、可预约天数）与每日营业时间共同计算
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeliveryTimeOptionsVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 配送方式: 1-自取, 2-商家配送, 3-外卖 */
    private Integer deliveryType;

    /** 该配送方式下店铺配置的最小准备/配送时间(分钟) */
    private Integer minMinutes;

    /** 可选档位间隔(分钟) */
    private Integer slotMinutes;

    /** 可预约天数(含当天) */
    private Integer reserveDays;

    /** 最快可选时间 yyyy-MM-dd HH:mm */
    private String earliestTime;

    /** 最快可选时间展示文案，如「今天 15:30」 */
    private String earliestLabel;

    /** 提示文案 */
    private String tip;

    /** 按天分组的时间档位 */
    private List<DayOptions> days;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DayOptions implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 日期 yyyy-MM-dd */
        private String date;

        /** 天展示文案：今天 / 明天 / 后天 / 10-28 周三 */
        private String dayLabel;

        /** 日期展示文案：MM-dd */
        private String dateLabel;

        /** 该天的可选档位 */
        private List<TimeSlot> slots;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TimeSlot implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 提交给后端的值 yyyy-MM-dd HH:mm */
        private String value;

        /** 展示文案 HH:mm */
        private String label;

        /** 是否可选 */
        private Boolean available;
    }
}
