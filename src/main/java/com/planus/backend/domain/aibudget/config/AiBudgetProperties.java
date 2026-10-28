package com.planus.backend.domain.aibudget.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** AI 예산 산출 서버 연동 설정. */
@ConfigurationProperties(prefix = "planus.ai.budget")
public class AiBudgetProperties {

    private String baseUrl = "https://8gcw31a7figzda.api.runpod.ai";
    private String apiKey;
    private String planusToken;
    private int connectTimeout = 10;
    private int readTimeout = 600;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getPlanusToken() {
        return planusToken;
    }

    public void setPlanusToken(String planusToken) {
        this.planusToken = planusToken;
    }

    public int getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(int connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public int getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(int readTimeout) {
        this.readTimeout = readTimeout;
    }
}
