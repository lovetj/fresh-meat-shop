package com.freshmeat.dto;

import lombok.Data;

@Data
public class OrderQueryDTO {

    /** 所属商铺ID */
    private String shopId;

    /** 状态筛选: null为全部, 0待支付, 1待接单, 2待配送/待自取, 3配送中, 4已完成, 5已取消, 6已拒单 */
    private Integer status;

    /** 配送方式: 1自取, 2商家配送 */
    private Integer deliveryType;

    /** 搜索关键字 (订单号/收货人/手机号/自提码) */
    private String keyword;

    /** 页码 */
    private Long pageNum = 1L;

    /** 每页条数 */
    private Long pageSize = 10L;
}
