package com.freshmeat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("shop_admin")
public class ShopAdmin implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 店铺UUID */
    private String shopId;

    /** 管理员用户UUID */
    private String userId;

    /** 店铺内角色: OWNER-店长/主管理员, ADMIN-管理员/店员 */
    private String roleType;

    /** 授权时间 */
    private LocalDateTime createTime;
}
