package com.freshmeat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.freshmeat.entity.User;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<User> {
}
