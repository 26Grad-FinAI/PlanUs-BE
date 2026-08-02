package com.planus.backend.global.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planus.backend.global.apiPayload.ApiResponse;
import com.planus.backend.global.apiPayload.code.GeneralErrorCode;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/** JWT 기반 무상태(Stateless) 보안 설정. {@code /api/auth/**}는 인증 없이 허용하고 나머지는 인증을 요구한다. */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter(
            JwtProvider jwtProvider, ObjectMapper objectMapper) {
        return new JwtAuthenticationFilter(jwtProvider, objectMapper);
    }

    /**
     * HTTP 보안 정책을 구성한다.
     *
     * <ul>
     *   <li>CSRF 비활성화 (JWT 사용으로 불필요)</li>
     *   <li>세션 미사용 (STATELESS)</li>
     *   <li>인증 실패 시 {@link ApiResponse} 형식의 JSON 401 응답 반환</li>
     *   <li>{@code /api/auth/**}: 인증 불필요</li>
     *   <li>그 외 모든 요청: 인증 필요</li>
     * </ul>
     */
    @Bean
    public SecurityFilterChain filterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            ObjectMapper objectMapper)
            throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(handling -> handling.authenticationEntryPoint(
                        (request, response, authException) -> {
                            response.setStatus(GeneralErrorCode.UNAUTHORIZED.getHttpStatus().value());
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter()
                                    .write(objectMapper.writeValueAsString(
                                            ApiResponse.onFailure(GeneralErrorCode.UNAUTHORIZED, null)));
                        }))
                .authorizeHttpRequests(auth -> auth.requestMatchers("/api/auth/**")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
