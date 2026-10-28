package com.planus.backend.domain.aibudget.service;

import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.Notification;
import com.planus.backend.domain.user.entity.UserAccount;
import com.planus.backend.domain.user.repository.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** FCM 푸시 알림 서비스. 알림 실패가 전체 플로우를 막지 않도록 warn 로그만 남긴다. */
@Service
@RequiredArgsConstructor
public class FcmService {

    private static final Logger log = LoggerFactory.getLogger(FcmService.class);

    private final UserAccountRepository userAccountRepository;

    /** 예산 산출 완료 푸시 알림을 전송한다. */
    public void sendBudgetPlanComplete(Long userId) {
        if (FirebaseApp.getApps().isEmpty()) {
            log.debug("Firebase 미초기화 — 푸시 알림 건너뜀 userId={}", userId);
            return;
        }

        UserAccount user = userAccountRepository.findById(userId).orElse(null);
        if (user == null || user.getFcmToken() == null || user.getFcmToken().isBlank()) {
            log.debug("FCM 토큰 없음 — 푸시 알림 건너뜀 userId={}", userId);
            return;
        }

        try {
            Message message = Message.builder()
                    .setToken(user.getFcmToken())
                    .setNotification(Notification.builder()
                            .setTitle("예산 산출 완료")
                            .setBody("AI가 이번 달 예산 배분을 완료했습니다. 확인해 보세요!")
                            .build())
                    .putData("type", "BUDGET_PLAN_COMPLETE")
                    .build();

            String messageId = FirebaseMessaging.getInstance().send(message);
            log.info("FCM 전송 성공 userId={} messageId={}", userId, messageId);
        } catch (Exception e) {
            log.warn("FCM 전송 실패 userId={}: {}", userId, e.getMessage());
        }
    }
}
