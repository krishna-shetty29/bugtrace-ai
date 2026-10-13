package com.bugtrace.backend;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log =
        LoggerFactory.getLogger(ApiExceptionHandler.class);

    public record ApiError(
        Instant timestamp,
        int status,
        String code,
        String message,
        String path,
        Map<String, String> fieldErrors
    ) {}

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> validation(
        MethodArgumentNotValidException exception,
        HttpServletRequest request
    ) {
        Map<String, String> fields = new LinkedHashMap<>();

        exception.getBindingResult().getFieldErrors().forEach(error ->
            fields.putIfAbsent(
                error.getField(), error.getDefaultMessage()
            )
        );

        return error(
            400, "VALIDATION_ERROR",
            "Check the required fields and their values.",
            request, fields
        );
    }

    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ApiError> invalidRequest(
        Exception exception,
        HttpServletRequest request
    ) {
        return error(
            400, "INVALID_REQUEST",
            "The request contains invalid JSON or an invalid value.",
            request, Map.of()
        );
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> notFound(
        ResponseStatusException exception,
        HttpServletRequest request
    ) {
        int status = exception.getStatusCode().value();

        return error(
            status,
            status == 404 ? "NOT_FOUND" : "REQUEST_ERROR",
            exception.getReason() == null
                ? "Request could not be completed."
                : exception.getReason(),
            request, Map.of()
        );
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> conflict(
        DataIntegrityViolationException exception,
        HttpServletRequest request
    ) {
        log.warn("Database constraint rejected a request", exception);

        return error(
            409, "DATA_CONFLICT",
            "The request conflicts with database constraints.",
            request, Map.of()
        );
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiError> databaseError(
        DataAccessException exception,
        HttpServletRequest request
    ) {
        log.error("Database operation failed", exception);

        return error(
            503, "DATABASE_UNAVAILABLE",
            "The database operation could not be completed.",
            request, Map.of()
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpectedError(
        Exception exception,
        HttpServletRequest request
    ) {
        log.error("Unexpected API error", exception);

        return error(
            500, "INTERNAL_ERROR",
            "An unexpected error occurred.",
            request, Map.of()
        );
    }

    private ResponseEntity<ApiError> error(
        int status,
        String code,
        String message,
        HttpServletRequest request,
        Map<String, String> fields
    ) {
        return ResponseEntity.status(status).body(
            new ApiError(
                Instant.now(),
                status,
                code,
                message,
                request.getRequestURI(),
                fields
            )
        );
    }
}