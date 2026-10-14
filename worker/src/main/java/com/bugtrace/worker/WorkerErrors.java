package com.bugtrace.worker;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class WorkerErrors {
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> status(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode()).body(Map.of(
            "status", exception.getStatusCode().value(),
            "message", exception.getReason() == null ? "Request rejected" : exception.getReason()
        ));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class,
        HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, Object>> invalid(Exception exception) {
        return ResponseEntity.badRequest().body(Map.of(
            "status", 400,
            "message", "Invalid structured job: check UUIDs, operation, selector, and timeout (1 to 600 seconds)"
        ));
    }
}
