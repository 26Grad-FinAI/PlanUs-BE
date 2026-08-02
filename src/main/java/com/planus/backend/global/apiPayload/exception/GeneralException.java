package com.planus.backend.global.apiPayload.exception;

import com.planus.backend.global.apiPayload.code.BaseErrorCode;
import java.util.Objects;
import lombok.Getter;

@Getter
public class GeneralException extends RuntimeException {

    private final BaseErrorCode errorCode;

    /**
     * 에러 코드의 기본 메시지를 사용하는 예외 생성자
     */
    public GeneralException(BaseErrorCode errorCode) {
        super(Objects.requireNonNull(errorCode, "errorCode must not be null").getMessage());
        this.errorCode = errorCode;
    }

    /**
     * 원본 예외를 cause로 전달하는 예외 생성자.
     * 예외 체인을 유지해 스택 트레이스에서 근본 원인을 추적할 수 있다.
     */
    public GeneralException(BaseErrorCode errorCode, Throwable cause) {
        super(Objects.requireNonNull(errorCode, "errorCode must not be null").getMessage(), cause);
        this.errorCode = errorCode;
    }

    /**
     * 상세 메시지를 지정할 수 있는 예외 생성자.
     * 상세 메시지가 없으면 에러 코드의 기본 메시지를 사용한다.
     */
    public GeneralException(BaseErrorCode errorCode, String detailMessage) {
        super(
                (detailMessage == null || detailMessage.isBlank())
                        ? Objects.requireNonNull(errorCode, "errorCode must not be null")
                                .getMessage()
                        : detailMessage);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode must not be null");
    }
}
