package com.freshmeat.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {
    /** 签名密钥 */
    private String secret;

    /** 基础默认过期时间 (毫秒)，默认7天 */
    private Long expiration = 604800000L;

    /** 顾客端 Token 过期时间 (毫秒)，默认30天，保障普通顾客免密常态体验 */
    private Long customerExpiration = 2592000000L;

    /** 管理员 Token 过期时间 (毫秒)，默认7天，保障店铺管理后台安全性与时效 */
    private Long adminExpiration = 604800000L;

    /** 请求头字段名 */
    private String header = "Authorization";

    /** Token 前缀 */
    private String prefix = "Bearer ";
}
