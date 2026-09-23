package com.dajin.system.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
    public ApiResponse<Void> uploadTooLarge(Exception e) { return ApiResponse.error(413000, "照片超过上传限制，单张最多5MB，请压缩后重试"); }
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    @ExceptionHandler(BusinessException.class)
    public ApiResponse<Void> business(BusinessException e) { return ApiResponse.error(e.getCode(), e.getMessage()); }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> validation(MethodArgumentNotValidException e) { return ApiResponse.error(400000, e.getBindingResult().getFieldError().getDefaultMessage()); }
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> other(Exception e) { log.error("Unhandled server error", e); return ApiResponse.error(500000, "服务器内部错误"); }
}
