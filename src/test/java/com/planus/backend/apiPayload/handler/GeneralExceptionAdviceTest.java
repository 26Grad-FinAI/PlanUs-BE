package com.planus.backend.apiPayload.handler;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.planus.backend.global.apiPayload.code.GeneralErrorCode;
import com.planus.backend.global.apiPayload.exception.GeneralException;
import com.planus.backend.global.apiPayload.handler.GeneralExceptionAdvice;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

class GeneralExceptionAdviceTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setup() {
        mockMvc = MockMvcBuilders.standaloneSetup(new GeneralExceptionAdviceTestController())
                .setControllerAdvice(new GeneralExceptionAdvice())
                .build();
    }

    /**
     * 테스트 전용 더미 컨트롤러.
     * <p>각 엔드포인트는 {@link GeneralExceptionAdvice}가 처리해야 할 예외를 의도적으로 발생시킨다.</p>
     */
    @Validated
    @RestController
    @Profile("general-exception-advice-test-only")
    @RequestMapping("/test")
    static class GeneralExceptionAdviceTestController {

        record TestRequest(@NotBlank(message = "이름은 필수입니다") String name) {}

        @GetMapping("/general-exception")
        public void generalException() {
            throw new GeneralException(GeneralErrorCode.NOT_FOUND);
        }

        @PostMapping("/valid")
        public void validBody(@Valid @RequestBody TestRequest request) {}

        @GetMapping("/type-mismatch/{id}")
        public void typeMismatch(@PathVariable Integer id) {}

        @GetMapping("/missing-param")
        public void missingParam(@RequestParam String name) {}

        @GetMapping("/server-error")
        public void serverError() {
            throw new RuntimeException("unexpected");
        }

        /**
         * standaloneSetup은 AOP를 실행하지 않으므로 {@code @Validated}가 동작하지 않는다.
         * Validator를 직접 실행해 {@link ConstraintViolationException}을 발생시킨다.
         */
        record NameBean(@NotBlank(message = "name은 비어 있을 수 없습니다") String name) {}

        @GetMapping("/constraint-violation")
        public void constraintViolation(@RequestParam String name) {
            var violations =
                    Validation.buildDefaultValidatorFactory().getValidator().validate(new NameBean(name));
            if (!violations.isEmpty()) {
                throw new ConstraintViolationException(violations);
            }
        }
    }

    /**
     * {@link GeneralException} 발생 시 해당 에러 코드의 HTTP 상태(404)와
     * 통일된 에러 응답 형식({@code isSuccess}, {@code code}, {@code errorDetail})이 올바르게 반환되는지 검증한다.
     */
    @Test
    void generalException_returns404() throws Exception {
        mockMvc.perform(get("/test/general-exception"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.isSuccess").value(false))
                .andExpect(jsonPath("$.code").value("COMMON_404_001"))
                .andExpect(jsonPath("$.errorDetail[0]").value("요청한 리소스를 찾을 수 없습니다."));
    }

    /**
     * {@code @Valid} 검증 실패 시 {@code MethodArgumentNotValidException}(BindException의 하위 타입)이
     * 400 상태와 함께 필드별 검증 오류 메시지를 {@code errorDetail}에 포함하여 반환하는지 검증한다.
     */
    @Test
    void validBody_blankName_returns400WithFieldError() throws Exception {
        mockMvc.perform(post("/test/valid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.isSuccess").value(false))
                .andExpect(jsonPath("$.code").value("COMMON_400_002"))
                .andExpect(jsonPath("$.errorDetail[0]").value("name: 이름은 필수입니다"));
    }

    /**
     * {@code @Validated} 환경에서 {@link ConstraintViolationException} 발생 시
     * 400 상태와 함께 제약 조건 위반 메시지가 {@code errorDetail}에 포함되는지 검증한다.
     */
    @Test
    void constraintViolation_blankParam_returns400() throws Exception {
        mockMvc.perform(get("/test/constraint-violation").param("name", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.isSuccess").value(false))
                .andExpect(jsonPath("$.code").value("COMMON_400_002"))
                .andExpect(jsonPath("$.errorDetail[0]", containsString("name은 비어 있을 수 없습니다")));
    }

    /**
     * {@code @PathVariable} 타입 불일치(예: Integer에 문자열 전달) 시
     * {@code MethodArgumentTypeMismatchException}이 400 상태로 처리되는지 검증한다.
     */
    @Test
    void typeMismatch_stringToInteger_returns400() throws Exception {
        mockMvc.perform(get("/test/type-mismatch/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.isSuccess").value(false))
                .andExpect(jsonPath("$.code").value("COMMON_400_002"))
                .andExpect(jsonPath("$.errorDetail[0]", containsString("타입이 올바르지 않습니다")));
    }

    /**
     * 필수 {@code @RequestParam}이 누락된 경우
     * {@code MissingServletRequestParameterException}이 400 상태로 처리되고,
     * 누락된 파라미터 이름이 {@code errorDetail}에 포함되는지 검증한다.
     */
    @Test
    void missingParam_returns400() throws Exception {
        mockMvc.perform(get("/test/missing-param"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.isSuccess").value(false))
                .andExpect(jsonPath("$.code").value("COMMON_400_002"))
                .andExpect(jsonPath("$.errorDetail[0]").value("name: 필수 파라미터가 누락되었습니다."));
    }

    /**
     * 지원하지 않는 HTTP 메서드(예: GET 전용 엔드포인트에 DELETE 요청) 사용 시
     * {@code HttpRequestMethodNotSupportedException}이 405 상태로 처리되고,
     * 요청한 메서드명이 {@code errorDetail}에 포함되는지 검증한다.
     */
    @Test
    void methodNotSupported_returns405() throws Exception {
        mockMvc.perform(delete("/test/general-exception"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.isSuccess").value(false))
                .andExpect(jsonPath("$.code").value("COMMON_405_001"))
                .andExpect(jsonPath("$.errorDetail[0]", containsString("DELETE")));
    }

    /**
     * 잘못된 JSON(파싱 불가능한 본문) 전송 시
     * {@code HttpMessageNotReadableException}이 400 상태로 처리되고,
     * JSON 작성 안내 메시지가 {@code errorDetail}에 포함되는지 검증한다.
     */
    @Test
    void notReadable_malformedJson_returns400() throws Exception {
        mockMvc.perform(post("/test/valid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.isSuccess").value(false))
                .andExpect(jsonPath("$.code").value("COMMON_400_001"))
                .andExpect(jsonPath("$.errorDetail[0]").value("요청 본문(JSON)을 올바르게 작성해 주세요."));
    }

    /**
     * 지원하지 않는 Content-Type(예: {@code text/plain}) 전송 시
     * {@code HttpMediaTypeNotSupportedException}이 415 상태로 처리되는지 검증한다.
     */
    @Test
    void mediaTypeNotSupported_textPlain_returns415() throws Exception {
        mockMvc.perform(post("/test/valid").contentType(MediaType.TEXT_PLAIN).content("plain text"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.isSuccess").value(false))
                .andExpect(jsonPath("$.code").value("COMMON_415_001"));
    }

    /**
     * 핸들러가 명시적으로 처리하지 않는 예외(예: {@link RuntimeException}) 발생 시
     * 500 상태의 폴백 응답이 반환되고, {@code errorDetail}이 노출되지 않는지 검증한다.
     */
    @Test
    void unhandledException_returns500() throws Exception {
        mockMvc.perform(get("/test/server-error"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.isSuccess").value(false))
                .andExpect(jsonPath("$.code").value("COMMON_500_001"))
                .andExpect(jsonPath("$.errorDetail").doesNotExist());
    }
}
