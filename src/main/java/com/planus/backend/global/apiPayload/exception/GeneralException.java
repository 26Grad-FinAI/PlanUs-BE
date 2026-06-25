package com.planus.backend.global.apiPayload.exception;

import com.planus.backend.global.apiPayload.code.BaseErrorCode;
import lombok.Getter;

import java.util.Objects;

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
     * 상세 메시지를 지정할 수 있는 예외 생성자.
     * 상세 메시지가 없으면 에러 코드의 기본 메시지를 사용한다.
     */
    public GeneralException(BaseErrorCode errorCode, String detailMessage) {
        super((detailMessage == null || detailMessage.isBlank())
                ? Objects.requireNonNull(errorCode, "errorCode must not be null").getMessage()
                : detailMessage);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode must not be null");
    }
}
