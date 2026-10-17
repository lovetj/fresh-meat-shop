package com.freshmeat.common;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Result<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 状态码: 200 成功, 401 未登录, 403 无权限, 500 业务/系统错误 */
    private Integer code;

    /** 响应消息提示 */
    private String message;

    /** 业务响应数据 */
    private T data;

    public static <T> Result<T> success() {
        return Result.<T>builder().code(200).message("操作成功").data(null).build();
    }

    public static <T> Result<T> success(T data) {
        return Result.<T>builder().code(200).message("操作成功").data(data).build();
    }

    public static <T> Result<T> success(String message, T data) {
        return Result.<T>builder().code(200).message(message).data(data).build();
    }

    public static <T> Result<T> error(String message) {
        return Result.<T>builder().code(500).message(message).data(null).build();
    }

    public static <T> Result<T> error(Integer code, String message) {
        return Result.<T>builder().code(code).message(message).data(null).build();
    }
}
