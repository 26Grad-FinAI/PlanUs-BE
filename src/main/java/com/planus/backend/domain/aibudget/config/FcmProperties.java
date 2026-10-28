package com.planus.backend.domain.aibudget.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** FCM 푸시 알림 설정. */
@ConfigurationProperties(prefix = "planus.fcm")
public class FcmProperties {

    private String serviceAccountPath;

    public String getServiceAccountPath() {
        return serviceAccountPath;
    }

    public void setServiceAccountPath(String serviceAccountPath) {
        this.serviceAccountPath = serviceAccountPath;
    }
}
