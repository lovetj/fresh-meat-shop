package com.freshmeat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.freshmeat.entity.OrderItem;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OrderItemMapper extends BaseMapper<OrderItem> {
}
