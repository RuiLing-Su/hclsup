package com.hcbt.hcisup.exception;

import com.hcbt.hcisup.pojo.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Created with IntelliJ IDEA
 *
 * @Author: Sout
 * @Date: 2024/10/20 下午1:52
 * @Description: 全局
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(Exception.class)
    public Result exception(Exception e) {
        log.error(e.getMessage());
        return Result.error("操作失败，请联系管理员!");
    }
}
