package com.planus.backend.domain.aibudget.client;

import com.planus.backend.domain.aibudget.client.dto.AiBudgetRequest;
import com.planus.backend.domain.aibudget.client.dto.AiBudgetResponse;
import com.planus.backend.domain.aibudget.config.AiBudgetProperties;
import java.net.http.HttpClient;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** AI 예산 산출 서버 HTTP 클라이언트. */
@Component
public class AiBudgetClient {

    private static final Logger log = LoggerFactory.getLogger(AiBudgetClient.class);

    private final RestClient client;
    private final AiBudgetProperties properties;

    public AiBudgetClient(AiBudgetProperties properties) {
        this.properties = properties;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getConnectTimeout()))
                .build());
        factory.setReadTimeout(Duration.ofSeconds(properties.getReadTimeout()));
        this.client = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(factory)
                .build();
    }

    /**
     * AI 예산 산출 서버에 예산 계획을 요청한다.
     *
     * @param request AI 예산 요청 DTO
     * @return AI 예산 응답 DTO
     * @throws AiBudgetClientException 서버 응답이 실패한 경우
     */
    public AiBudgetResponse requestPlan(AiBudgetRequest request) {
        log.info("AI 예산 산출 요청 시작 userId={}", request.userId());

        return client.post()
                .uri("/v1/plan")
                .header("Authorization", "Bearer " + properties.getApiKey())
                .header("X-PlanUS-Token", properties.getPlanusToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, (req, resp) -> {
                    log.error("AI 예산 서버 클라이언트 에러: status={}", resp.getStatusCode());
                    throw new AiBudgetClientException("AI 예산 서버 요청 실패: " + resp.getStatusCode());
                })
                .onStatus(HttpStatusCode::is5xxServerError, (req, resp) -> {
                    log.error("AI 예산 서버 서버 에러: status={}", resp.getStatusCode());
                    throw new AiBudgetClientException("AI 예산 서버 내부 오류: " + resp.getStatusCode());
                })
                .body(AiBudgetResponse.class);
    }

    /** AI 서버 health check. 서버를 깨우는 용도로 사용. */
    public String healthCheck() {
        return client.get()
                .uri("/health")
                .header("Authorization", "Bearer " + properties.getApiKey())
                .header("X-PlanUS-Token", properties.getPlanusToken())
                .retrieve()
                .body(String.class);
    }

    /** AI 예산 서버 호출 실패 예외. */
    public static class AiBudgetClientException extends RuntimeException {
        public AiBudgetClientException(String message) {
            super(message);
        }

        public AiBudgetClientException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
