package com.freshmeat.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ApiModel("订单状态统计VO")
public class OrderStatusCountVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty("全部订单数")
    private Long allCount;

    @ApiModelProperty("待接单数量")
    private Long pendingAcceptCount;

    @ApiModelProperty("待配送/待自取数量")
    private Long acceptedCount;

    @ApiModelProperty("配送中数量")
    private Long deliveringCount;

    @ApiModelProperty("待支付数量")
    private Long pendingPayCount;

    @ApiModelProperty("已完成数量")
    private Long completedCount;

    @ApiModelProperty("已取消或已拒单数量")
    private Long closedCount;
}
