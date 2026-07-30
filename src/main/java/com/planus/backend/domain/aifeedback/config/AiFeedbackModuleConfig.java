package com.planus.backend.domain.aifeedback.config;

import java.time.Clock;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * AI-01 모듈 설정. 메인 앱에서 이 패키지가 컴포넌트 스캔되면 자동 적용.
 * (이미 @EnableScheduling 이 있다면 중복 제거.)
 */
@Configuration
@EnableConfigurationProperties(AiFeedbackProperties.class)
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "10m")
public class AiFeedbackModuleConfig {

    /** 기본 시스템 시계. 테스트에서 Clock.fixed(...)로 교체 가능. */
    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemDefaultZone();
    }

    /** ShedLock 락 저장소. 기존 PostgreSQL DataSource를 그대로 사용한다. */
    @Bean
    LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .usingDbTime()
                .build());
    }
}
