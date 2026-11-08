package com.scanner.exception.server;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;
import java.util.NoSuchElementException;

/** Hataları RFC 7807 (application/problem+json) olarak döndürür. */
@RestControllerAdvice
class ApiErrorHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badRequest(IllegalArgumentException e) {
        return problem(HttpStatus.BAD_REQUEST, "Geçersiz istek", e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail unreadable(HttpMessageNotReadableException e) {
        Throwable cause = e.getMostSpecificCause();
        return problem(HttpStatus.BAD_REQUEST, "Geçersiz JSON", cause != null ? cause.getMessage() : e.getMessage());
    }

    @ExceptionHandler(NoSuchElementException.class)
    ProblemDetail notFound(NoSuchElementException e) {
        return problem(HttpStatus.NOT_FOUND, "Bulunamadı", e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail conflict(IllegalStateException e) {
        return problem(HttpStatus.CONFLICT, "Çakışma", e.getMessage());
    }

    @ExceptionHandler(java.sql.SQLException.class)
    ProblemDetail database(java.sql.SQLException e) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Veritabanı hatası", e.getMessage());
    }

    @ExceptionHandler(IOException.class)
    ProblemDetail io(IOException e) {
        // Config.load ayar dosyası bulunamadığında / okunamadığında IOException atar
        return problem(HttpStatus.BAD_REQUEST, "Ayar veya dosya hatası", e.getMessage());
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setTitle(title);
        return p;
    }
}
