package com.freshmeat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 快递配送试算结果：是否可发货、运费、命中的特殊地区与提示文案
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExpressCheckVO {

    /** 是否可配送 */
    private Boolean canDeliver;

    /** 快递运费(元) */
    private BigDecimal fee;

    /** 命中的特殊地区关键字，未命中为 null */
    private String matchedRegion;

    /** 提示文案 */
    private String message;
}
