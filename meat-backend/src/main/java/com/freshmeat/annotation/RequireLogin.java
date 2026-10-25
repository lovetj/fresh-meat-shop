package com.freshmeat.annotation;

import java.lang.annotation.*;

/**
 * 标注接口必须登录后才能访问
 *
 * 由 JwtInterceptor 统一校验，避免各个 Service 漏写登录判断导致接口裸奔。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequireLogin {
}
