package com.freshmeat.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 文件存储与访问服务配置
 * 
 * 架构规范说明：
 * 文件访问外网域名/基地址（baseServer）已统一下放至前端配置（如小程序 config.js 中的 fileBaseServer）并由前端动态组装拼接。
 * 后端仅需管理文件物理存储磁盘目录（basePath），实现数据库纯相对路径落库与服务解耦。
 */
@Component
@ConfigurationProperties(prefix = "file")
@Data
public class FileConfigProperties {

    /**
     * 文件外部访问基础地址（已废弃/可选，前端统一从 config.js 动态拼接）
     */
    private String baseServer = "";

    /**
     * 文件物理存储目录，如 D:\soft\apache-tomcat-8.5.63\webapps\meat_file
     */
    private String basePath;
}
