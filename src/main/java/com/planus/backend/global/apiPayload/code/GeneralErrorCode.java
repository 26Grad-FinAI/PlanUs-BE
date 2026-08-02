package com.planus.backend.global.apiPayload.code;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum GeneralErrorCode implements BaseErrorCode {
    BAD_REQUEST(HttpStatus.BAD_REQUEST, "COMMON_400_001", "잘못된 요청입니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "AUTH_401_001", "인증이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "AUTH_403_001", "요청이 거부되었습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "COMMON_404_001", "요청한 리소스를 찾을 수 없습니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON_500_001", "예기치 않은 서버 에러가 발생했습니다."),

    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "COMMON_400_002", "요청 값 검증에 실패했습니다."),

    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "COMMON_405_001", "지원하지 않는 HTTP 메서드입니다."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "COMMON_415_001", "지원하지 않는 Content-Type입니다."),

    // Auth
    INVALID_EMAIL_FORMAT(HttpStatus.BAD_REQUEST, "AUTH_400_001", "올바른 이메일 형식을 입력해 주세요."),
    PASSWORD_MISMATCH(HttpStatus.BAD_REQUEST, "AUTH_400_002", "비밀번호가 일치하지 않습니다."),
    PASSWORD_TOO_WEAK(HttpStatus.BAD_REQUEST, "AUTH_400_003", "비밀번호는 8자 이상, 영문과 숫자를 포함해야 합니다."),
    TERMS_NOT_AGREED(HttpStatus.BAD_REQUEST, "AUTH_400_004", "이용 약관 및 개인정보 처리방침에 동의해 주세요."),
    EMAIL_DUPLICATE(HttpStatus.CONFLICT, "AUTH_409_001", "이미 사용 중인 이메일입니다."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "AUTH_401_002", "이메일 또는 비밀번호가 올바르지 않습니다."),
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH_401_003", "유효하지 않은 토큰입니다."),
    EXPIRED_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH_401_004", "만료된 토큰입니다."),
    SOCIAL_LOGIN_EMAIL_CONFLICT(HttpStatus.CONFLICT, "AUTH_409_002", "이미 다른 방식으로 가입된 이메일입니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
