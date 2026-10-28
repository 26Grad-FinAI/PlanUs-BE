package com.planus.backend.global.config;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 비동기 처리 설정. AI 예산 산출 등 장시간 작업용 전용 스레드풀. */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean("aiBudgetExecutor")
    public Executor aiBudgetExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("ai-budget-");
        executor.initialize();
        return executor;
    }
}
