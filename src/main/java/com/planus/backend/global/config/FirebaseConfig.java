package com.planus.backend.global.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.planus.backend.domain.aibudget.config.FcmProperties;
import jakarta.annotation.PostConstruct;
import java.io.FileInputStream;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Firebase Admin SDK 초기화. 서비스 계정 경로 미설정 시 skip (로컬 개발 대응). */
@Configuration
@EnableConfigurationProperties(FcmProperties.class)
public class FirebaseConfig {

    private static final Logger log = LoggerFactory.getLogger(FirebaseConfig.class);

    private final FcmProperties fcmProperties;

    public FirebaseConfig(FcmProperties fcmProperties) {
        this.fcmProperties = fcmProperties;
    }

    @PostConstruct
    public void init() {
        String path = fcmProperties.getServiceAccountPath();
        if (path == null || path.isBlank()) {
            log.info("FCM 서비스 계정 경로 미설정 — Firebase 초기화 건너뜀");
            return;
        }
        if (!FirebaseApp.getApps().isEmpty()) {
            log.info("Firebase 이미 초기화됨");
            return;
        }
        try (FileInputStream fis = new FileInputStream(path)) {
            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(fis))
                    .build();
            FirebaseApp.initializeApp(options);
            log.info("Firebase 초기화 완료");
        } catch (IOException e) {
            log.warn("Firebase 초기화 실패: {}", e.getMessage());
        }
    }
}
