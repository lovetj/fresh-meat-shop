package com.freshmeat.annotation;

import java.lang.annotation.*;

/**
 * 标注接口需要管理员权限
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequireAdmin {
}
