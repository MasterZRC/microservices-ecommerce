package com.ecommerce.admin.exception;

import com.ecommerce.admin.dto.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(RuntimeException.class)
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<Void> handleRuntimeException(RuntimeException e, HttpServletRequest request) {
        log.error("Runtime error at {}: {}", request.getRequestURI(), e.getMessage());
        return ApiResponse.error(e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleIllegalArgumentException(IllegalArgumentException e, HttpServletRequest request) {
        log.error("Invalid argument at {}: {}", request.getRequestURI(), e.getMessage());
        return ApiResponse.error(400, e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleValidationException(MethodArgumentNotValidException e, HttpServletRequest request) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        log.error("Validation failed at {}: {}", request.getRequestURI(), message);
        return ApiResponse.error(400, message);
    }

    @ExceptionHandler(BindException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleBindException(BindException e, HttpServletRequest request) {
        String message = e.getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        log.error("Binding failed at {}: {}", request.getRequestURI(), message);
        return ApiResponse.error(400, message);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleMissingParamException(MissingServletRequestParameterException e, HttpServletRequest request) {
        String message = "缺少参数: " + e.getParameterName();
        log.error("Missing parameter at {}: {}", request.getRequestURI(), message);
        return ApiResponse.error(400, message);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleTypeMismatchException(MethodArgumentTypeMismatchException e, HttpServletRequest request) {
        String message = "参数类型错误: " + e.getName();
        log.error("Type mismatch at {}: {}", request.getRequestURI(), message);
        return ApiResponse.error(400, message);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public ApiResponse<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException e, HttpServletRequest request) {
        String message = "不支持的请求方法: " + e.getMethod();
        log.error("Method not supported at {}: {}", request.getRequestURI(), message);
        return ApiResponse.error(405, message);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleNoResourceFound(NoResourceFoundException e, HttpServletRequest request) {
        log.error("Resource not found at {}: {}", request.getRequestURI(), e.getMessage());
        return ApiResponse.error(404, "资源不存在");
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleGenericException(Exception e, HttpServletRequest request) {
        log.error("Unexpected error at {}: {}", request.getRequestURI(), e.getMessage(), e);
        return ApiResponse.error(500, "服务器内部错误: " + e.getMessage());
    }
}
