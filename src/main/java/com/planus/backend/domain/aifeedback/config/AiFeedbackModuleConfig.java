package com.planus.backend.domain.aifeedback.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

/**
 * AI-01 모듈 설정. 메인 앱에서 이 패키지가 컴포넌트 스캔되면 자동 적용.
 * (이미 @EnableScheduling 이 있다면 중복 제거.)
 */
@Configuration
@EnableConfigurationProperties(AiFeedbackProperties.class)
@EnableScheduling
public class AiFeedbackModuleConfig {

    /** 기본 시스템 시계. 테스트에서 Clock.fixed(...)로 교체 가능. */
    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemDefaultZone();
    }
}
