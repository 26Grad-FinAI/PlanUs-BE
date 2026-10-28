package com.planus.backend.domain.aibudget.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** AI 예산 산출 모듈 설정. */
@Configuration
@EnableConfigurationProperties(AiBudgetProperties.class)
public class AiBudgetModuleConfig {}
