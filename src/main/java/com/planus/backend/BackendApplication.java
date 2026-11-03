package com.planus.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(
        exclude = {
            org.springframework.ai.model.anthropic.autoconfigure.AnthropicChatAutoConfiguration.class,
            org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration.class
        })
public class BackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(BackendApplication.class, args);
    }
}
