package com.planus.backend.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/** JPA 설정. @CreatedDate / @LastModifiedDate 자동 주입을 활성화한다. */
@Configuration
@EnableJpaAuditing
public class JpaConfig {}
