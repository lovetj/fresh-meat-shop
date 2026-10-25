package com.freshmeat.exception;

import com.freshmeat.common.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.BindException;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusinessException(BusinessException e) {
        log.warn("业务异常: code={}, message={}", e.getCode(), e.getMessage());
        return Result.error(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidException(MethodArgumentNotValidException e) {
        BindingResult bindingResult = e.getBindingResult();
        String message = bindingResult.getFieldErrors().stream()
                .map(err -> err.getDefaultMessage())
                .reduce((m1, m2) -> m1 + "; " + m2)
                .orElse("参数校验失败");
        log.warn("参数校验异常: {}", message);
        return Result.error(400, message);
    }

    @ExceptionHandler(BindException.class)
    public Result<Void> handleBindException(BindException e) {
        String message = e.getAllErrors().stream()
                .map(err -> err.getDefaultMessage())
                .reduce((m1, m2) -> m1 + "; " + m2)
                .orElse("参数绑定失败");
        log.warn("参数绑定异常: {}", message);
        return Result.error(400, message);
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        // 完整堆栈仅记录日志，响应体不返回异常原文，避免泄露内部实现与SQL细节
        log.error("系统未捕获未知异常: ", e);
        return Result.error(500, "系统繁忙，请稍后再试");
    }
}
