package com.planus.backend.apiPayload.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.planus.backend.global.apiPayload.code.GeneralErrorCode;
import com.planus.backend.global.apiPayload.exception.GeneralException;
import org.junit.jupiter.api.Test;

class GeneralExceptionTest {

    /**
     * 에러 코드만으로 생성한 경우, 예외의 message가 에러 코드의 기본 메시지와 동일하고
     * {@code getErrorCode()}가 전달한 에러 코드를 정확히 반환하는지 검증한다.
     */
    @Test
    void constructor_withErrorCode_messageIsErrorCodeMessage() {
        GeneralException ex = new GeneralException(GeneralErrorCode.NOT_FOUND);

        assertThat(ex.getMessage()).isEqualTo(GeneralErrorCode.NOT_FOUND.getMessage());
        assertThat(ex.getErrorCode()).isEqualTo(GeneralErrorCode.NOT_FOUND);
    }

    /**
     * 에러 코드에 {@code null}을 전달하면 {@link NullPointerException}이 발생하는지 검증한다.
     */
    @Test
    void constructor_withNullErrorCode_throwsNullPointerException() {
        assertThatThrownBy(() -> new GeneralException(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("errorCode must not be null");
    }

    /**
     * 상세 메시지를 함께 전달한 경우, 에러 코드의 기본 메시지 대신
     * 전달된 상세 메시지가 예외의 message로 사용되는지 검증한다.
     */
    @Test
    void constructor_withDetailMessage_usesDetailMessage() {
        GeneralException ex = new GeneralException(GeneralErrorCode.NOT_FOUND, "ID: 1 회원 없음");

        assertThat(ex.getMessage()).isEqualTo("ID: 1 회원 없음");
        assertThat(ex.getErrorCode()).isEqualTo(GeneralErrorCode.NOT_FOUND);
    }

    /**
     * 상세 메시지가 {@code null}이면 에러 코드의 기본 메시지로 폴백되는지 검증한다.
     */
    @Test
    void constructor_withNullDetailMessage_fallsBackToErrorCodeMessage() {
        GeneralException ex = new GeneralException(GeneralErrorCode.NOT_FOUND, null);

        assertThat(ex.getMessage()).isEqualTo(GeneralErrorCode.NOT_FOUND.getMessage());
    }

    /**
     * 상세 메시지가 공백 문자열이면 에러 코드의 기본 메시지로 폴백되는지 검증한다.
     */
    @Test
    void constructor_withBlankDetailMessage_fallsBackToErrorCodeMessage() {
        GeneralException ex = new GeneralException(GeneralErrorCode.NOT_FOUND, "   ");

        assertThat(ex.getMessage()).isEqualTo(GeneralErrorCode.NOT_FOUND.getMessage());
    }

    /**
     * 두 번째 생성자에서도 에러 코드가 {@code null}이면
     * {@link NullPointerException}이 발생하는지 검증한다.
     */
    @Test
    void constructor_withNullErrorCodeAndDetailMessage_throwsNullPointerException() {
        assertThatThrownBy(() -> new GeneralException(null, "detail"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("errorCode must not be null");
    }
}
