package com.freshmeat.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "wechat")
public class WechatProperties {

    private Miniapp miniapp = new Miniapp();
    private Pay pay = new Pay();

    @Data
    public static class Miniapp {
        private String appid;
        private String secret;
        private Boolean mock = true;
    }

    @Data
    public static class Pay {
        private String mchid;
        private String apiV3Key;
        private String certSerialNo;
        private String privateKeyPath;
        private String notifyUrl;
        private Boolean mock = true;
    }
}
