package com.freshmeat.dto;

import lombok.Data;

@Data
public class OrderStatusUpdateDTO {

    /** 操作原因 (如拒单原因、取消原因) */
    private String reason;
}
