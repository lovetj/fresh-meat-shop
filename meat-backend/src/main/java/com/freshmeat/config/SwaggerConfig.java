package com.freshmeat.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import springfox.documentation.builders.ApiInfoBuilder;
import springfox.documentation.builders.PathSelectors;
import springfox.documentation.builders.RequestHandlerSelectors;
import springfox.documentation.builders.RequestParameterBuilder;
import springfox.documentation.schema.ScalarType;
import springfox.documentation.service.ApiInfo;
import springfox.documentation.service.Contact;
import springfox.documentation.service.ParameterType;
import springfox.documentation.service.RequestParameter;
import springfox.documentation.spi.DocumentationType;
import springfox.documentation.spring.web.plugins.Docket;
import springfox.documentation.swagger2.annotations.EnableSwagger2;

import java.util.ArrayList;
import java.util.List;

/**
 * Swagger / Knife4j 接口文档配置
 */
@Configuration
@EnableSwagger2
public class SwaggerConfig {

    @Bean
    public Docket createRestApi() {
        // 全局请求头配置：增加 Authorization Token 参数方便在线调试
        List<RequestParameter> parameters = new ArrayList<>();
        parameters.add(new RequestParameterBuilder()
                .name("Authorization")
                .description("用户登录认证Token (格式: Bearer xxx)")
                .in(ParameterType.HEADER)
                .required(false)
                .query(q -> q.model(m -> m.scalarModel(ScalarType.STRING)))
                .build());

        return new Docket(DocumentationType.SWAGGER_2)
                .apiInfo(apiInfo())
                .select()
                .apis(RequestHandlerSelectors.basePackage("com.freshmeat.controller"))
                .paths(PathSelectors.any())
                .build()
                .globalRequestParameters(parameters);
    }

    private ApiInfo apiInfo() {
        return new ApiInfoBuilder()
                .title("维琼鲜肉铺 - 同城肉铺配送系统接口文档")
                .description("提供顾客端与掌柜管理端全套 RESTful API 接口，包括微信登录、商品展示、同城配送测距、订单状态流转与微信支付等。")
                .contact(new Contact("维琼鲜肉铺研发团队", "", ""))
                .version("1.0.0")
                .build();
    }
}
