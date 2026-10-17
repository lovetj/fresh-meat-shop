package com.freshmeat;

import org.mybatis.spring.annotation.MapperScan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import java.net.InetAddress;
import java.net.UnknownHostException;

@SpringBootApplication
@MapperScan("com.freshmeat.mapper")
public class MeatApplication {
    private static final Logger log = LoggerFactory.getLogger(MeatApplication.class);

    public static void main(String[] args) {
        ConfigurableApplicationContext application = SpringApplication.run(MeatApplication.class, args);
        Environment env = application.getEnvironment();
        String ip = "localhost";
        try {
            ip = InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            log.warn("获取本机IP地址失败: {}", e.getMessage());
        }
        String port = env.getProperty("server.port", "8080");
        String path = env.getProperty("server.servlet.context-path", "");
        if (!StringUtils.hasText(path)) {
            path = "";
        }

        log.info("\n" +
                        "----------------------------------------------------------\n" +
                        "Application '{}' is running! Access URLs:\n" +
                        "Local:    http://localhost:{}{}\n" +
                        "External: http://{}:{}{}\n" +
                        "Doc:      http://localhost:{}{}/doc.html\n" +
                        "----------------------------------------------------------",
                env.getProperty("spring.application.name", "meat-backend"),
                port,
                path,
                ip,
                port,
                path,
                port,
                path
        );
    }
}
