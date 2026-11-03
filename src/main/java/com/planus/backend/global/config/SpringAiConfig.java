package com.planus.backend.global.config;

import org.springframework.ai.model.anthropic.autoconfigure.AnthropicChatAutoConfiguration;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * ANTHROPIC_API_KEY가 설정된 경우에만 Spring AI Anthropic 자동 구성을 활성화한다.
 *
 * <p>API 키가 없으면 이 Configuration이 로드되지 않아 ChatClient.Builder 빈이 생성되지 않으며,
 * {@link com.planus.backend.domain.aifeedback.ai.ActionEnricher}는 {@code @Nullable}로 null을 주입받아
 * 기본 fallback 로직을 사용한다.
 */
@Configuration
@ConditionalOnProperty(name = "spring.ai.anthropic.api-key")
@Import({AnthropicChatAutoConfiguration.class, ChatClientAutoConfiguration.class})
public class SpringAiConfig {}
