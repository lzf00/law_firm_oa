package com.zoro.legaloa.common;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.validation.FieldError;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ApiError> handleBusiness(BusinessException exception, Locale locale) {
        return ResponseEntity.status(exception.status())
                .body(ApiError.of(
                        exception.code(),
                        ApiMessageCatalog.message(exception.code(), exception.getMessage(), locale)
                ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleValidation(
            MethodArgumentNotValidException exception,
            Locale locale
    ) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (FieldError error : exception.getBindingResult().getFieldErrors()) {
            fields.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        return ResponseEntity.badRequest().body(
                new ApiError(
                        "VALIDATION_FAILED",
                        ApiMessageCatalog.message(
                                "VALIDATION_FAILED", "请求参数校验失败", locale
                        ),
                        Instant.now(),
                        fields
                )
        );
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> handleTypeMismatch(
            MethodArgumentTypeMismatchException exception,
            Locale locale
    ) {
        String fallback = "请求参数格式错误：" + exception.getName();
        String message = ApiMessageCatalog.message("PARAMETER_INVALID", fallback, locale);
        if (locale != null && "en".equalsIgnoreCase(locale.getLanguage())) {
            message += ": " + exception.getName();
        }
        return ResponseEntity.badRequest().body(
                ApiError.of("PARAMETER_INVALID", message)
        );
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ApiError> handleMethodValidation(
            HandlerMethodValidationException exception,
            Locale locale
    ) {
        return ResponseEntity.badRequest().body(
                ApiError.of(
                        "VALIDATION_FAILED",
                        ApiMessageCatalog.message(
                                "VALIDATION_FAILED", "请求参数校验失败", locale
                        )
                )
        );
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> handleConflict(
            DataIntegrityViolationException exception,
            Locale locale
    ) {
        log.warn(
                "Database constraint rejected request: {}",
                exception.getMostSpecificCause().getMessage()
        );
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of(
                        "RESOURCE_CONFLICT",
                        ApiMessageCatalog.message(
                                "RESOURCE_CONFLICT", "数据已存在或状态发生冲突", locale
                        )
                ));
    }

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    void handleClientDisconnect(AsyncRequestNotUsableException exception) {
        // The client has already closed the socket, so attempting to serialize an error
        // response would only trigger another broken-pipe exception.
        log.debug("Client disconnected before the response could be completed: {}",
                exception.getMessage());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(Exception exception, Locale locale) {
        log.error("Unhandled request failure", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of(
                        "INTERNAL_ERROR",
                        ApiMessageCatalog.message(
                                "INTERNAL_ERROR", "系统处理失败，请联系管理员", locale
                        )
                ));
    }
}
