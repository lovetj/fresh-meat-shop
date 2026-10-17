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
@TableName("order_item")
public class OrderItem implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 所属订单ID */
    private String orderId;

    /** 商品ID */
    private String productId;

    /** 商品名称 */
    private String productName;

    /** 商品图片 */
    private String productImage;

    /** 规格说明 (如: 1斤 / 切薄片) */
    private String specInfo;

    /** 购买单价 */
    private BigDecimal price;

    /** 购买数量/份数 */
    private Integer quantity;

    /** 小计金额 */
    private BigDecimal subtotal;

    /** 创建时间 */
    private LocalDateTime createTime;
}
