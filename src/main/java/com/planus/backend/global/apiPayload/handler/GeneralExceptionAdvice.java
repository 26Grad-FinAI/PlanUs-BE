package com.planus.backend.global.apiPayload.handler;

import com.planus.backend.global.apiPayload.ApiResponse;
import com.planus.backend.global.apiPayload.code.BaseErrorCode;
import com.planus.backend.global.apiPayload.code.GeneralErrorCode;
import com.planus.backend.global.apiPayload.exception.GeneralException;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 전역 예외 처리 어드바이스.
 * <p>컨트롤러에서 발생하는 공통 예외를 가로채어 통일된 {@link ApiResponse} 형식으로 변환한다.</p>
 */
@Slf4j
@RequiredArgsConstructor
@RestControllerAdvice
public class GeneralExceptionAdvice {

    /**
     * 애플리케이션 커스텀 예외({@link GeneralException})를 처리한다.
     * <p>예외에 포함된 {@link BaseErrorCode}의 HTTP 상태 코드와 메시지를 그대로 응답에 반영한다.</p>
     */
    @ExceptionHandler(GeneralException.class)
    public ResponseEntity<ApiResponse<?>> handleGeneralException(GeneralException ex) {
        BaseErrorCode ec = ex.getErrorCode();
        log.warn("[GeneralException] code={}, message={}", ec.getCode(), ex.getMessage());
        return ResponseEntity.status(ec.getHttpStatus()).body(ApiResponse.onFailure(ec, List.of(ex.getMessage())));
    }

    /**
     * {@code @Valid} DTO 검증 실패 시 발생하는 {@link BindException}을 처리한다.
     * <p>{@code MethodArgumentNotValidException}은 {@code BindException}의 하위 타입이므로 함께 처리된다.
     * 필드별 검증 오류 메시지를 {@code errorDetail} 리스트에 담아 400 응답을 반환한다.</p>
     */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiResponse<?>> handleBindException(BindException ex) {
        BaseErrorCode ec = GeneralErrorCode.VALIDATION_ERROR;

        List<String> detail = ex.getBindingResult().getAllErrors().stream()
                .map(error -> {
                    if (error instanceof FieldError fe) {
                        return fe.getField() + ": " + fe.getDefaultMessage();
                    }
                    return error.getObjectName() + ": " + error.getDefaultMessage();
                })
                .toList();

        log.debug("[ValidationFail] {}", detail);
        return ResponseEntity.status(ec.getHttpStatus()).body(ApiResponse.onFailure(ec, detail));
    }

    /**
     * {@code @Validated}가 적용된 컨트롤러에서 {@code @PathVariable} 또는 {@code @RequestParam}
     * 검증 실패 시 발생하는 {@link ConstraintViolationException}을 처리한다.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<?>> handleConstraintViolation(ConstraintViolationException ex) {
        BaseErrorCode ec = GeneralErrorCode.VALIDATION_ERROR;

        List<String> detail = ex.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .toList();

        log.debug("[ConstraintViolation] {}", detail);
        return ResponseEntity.status(ec.getHttpStatus()).body(ApiResponse.onFailure(ec, detail));
    }

    /**
     * 요청 파라미터의 타입이 기대 타입과 일치하지 않을 때 발생하는
     * {@link MethodArgumentTypeMismatchException}을 처리한다.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<?>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        BaseErrorCode ec = GeneralErrorCode.VALIDATION_ERROR;

        String requiredType =
                ex.getRequiredType() == null ? "알 수 없음" : ex.getRequiredType().getSimpleName();
        String detail = ex.getName() + ": 타입이 올바르지 않습니다. (기대 타입: " + requiredType + ")";
        log.debug("[TypeMismatch] parameter={}, requiredType={}", ex.getName(), requiredType);
        return ResponseEntity.status(ec.getHttpStatus()).body(ApiResponse.onFailure(ec, List.of(detail)));
    }

    /**
     * 필수 {@code @RequestParam}이 누락되었을 때 발생하는
     * {@link MissingServletRequestParameterException}을 처리한다.
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<?>> handleMissingParam(MissingServletRequestParameterException ex) {
        BaseErrorCode ec = GeneralErrorCode.VALIDATION_ERROR;

        String detail = ex.getParameterName() + ": 필수 파라미터가 누락되었습니다.";
        log.debug("[MissingParam] {}", detail);
        return ResponseEntity.status(ec.getHttpStatus()).body(ApiResponse.onFailure(ec, List.of(detail)));
    }

    /**
     * JSON 파싱 실패 또는 요청 본문이 읽을 수 없는 형식일 때 발생하는
     * {@link HttpMessageNotReadableException}을 처리한다.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<?>> handleNotReadable(HttpMessageNotReadableException ex) {
        BaseErrorCode ec = GeneralErrorCode.BAD_REQUEST;

        log.warn("[HttpMessageNotReadable] malformed request body");
        log.debug("[HttpMessageNotReadable] detail", ex);
        return ResponseEntity.status(ec.getHttpStatus())
                .body(ApiResponse.onFailure(ec, List.of("요청 본문(JSON)을 올바르게 작성해 주세요.")));
    }

    /**
     * 지원하지 않는 HTTP 메서드로 요청했을 때 발생하는
     * {@link HttpRequestMethodNotSupportedException}을 처리한다. (405)
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<?>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        BaseErrorCode ec = GeneralErrorCode.METHOD_NOT_ALLOWED;

        String detail = "지원하지 않는 HTTP 메서드입니다: " + ex.getMethod();
        HttpHeaders headers = new HttpHeaders();
        if (ex.getSupportedHttpMethods() != null) {
            headers.setAllow(ex.getSupportedHttpMethods());
        }
        return ResponseEntity.status(ec.getHttpStatus())
                .headers(headers)
                .body(ApiResponse.onFailure(ec, List.of(detail)));
    }

    /**
     * 지원하지 않는 Content-Type으로 요청했을 때 발생하는
     * {@link HttpMediaTypeNotSupportedException}을 처리한다. (415)
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<?>> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex) {
        BaseErrorCode ec = GeneralErrorCode.UNSUPPORTED_MEDIA_TYPE;

        String detail = "지원하지 않는 Content-Type 입니다: " + ex.getContentType();
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(ex.getSupportedMediaTypes());
        return ResponseEntity.status(ec.getHttpStatus())
                .headers(headers)
                .body(ApiResponse.onFailure(ec, List.of(detail)));
    }

    /**
     * Spring Framework 6.1+ 에서 {@code @Validated} 컨트롤러의 {@code @PathVariable},
     * {@code @RequestParam} 등 메서드 파라미터 검증 실패 시 발생하는
     * {@link HandlerMethodValidationException}을 처리한다.
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiResponse<?>> handleHandlerMethodValidation(HandlerMethodValidationException ex) {
        BaseErrorCode ec = GeneralErrorCode.VALIDATION_ERROR;

        List<String> detail = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream().map(error -> {
                    if (error instanceof FieldError fe) {
                        return fe.getField() + ": " + fe.getDefaultMessage();
                    }
                    return result.getMethodParameter().getParameterName() + ": " + error.getDefaultMessage();
                }))
                .toList();

        log.debug("[HandlerMethodValidation] {}", detail);
        return ResponseEntity.status(ec.getHttpStatus()).body(ApiResponse.onFailure(ec, detail));
    }

    /**
     * 위의 핸들러에서 처리되지 않은 모든 예외를 잡는 폴백 핸들러.
     * <p>내부 오류 상세를 클라이언트에 노출하지 않고 500 응답을 반환한다.</p>
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<?>> handleException(Exception ex) {
        log.error("Unhandled exception", ex);

        BaseErrorCode ec = GeneralErrorCode.INTERNAL_SERVER_ERROR;
        return ResponseEntity.status(ec.getHttpStatus()).body(ApiResponse.onFailure(ec, List.of()));
    }
}
