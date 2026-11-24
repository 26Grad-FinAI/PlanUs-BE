package com.planus.backend.global.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.planus.backend.domain.user.repository.UserAccountRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(
        controllers = SecurityConfigTest.TestController.class,
        properties = "planus.cors.allowed-origins=http://localhost:8081")
@Import(SecurityConfig.class)
class SecurityConfigTest {

    @RestController
    static class TestController {
        @GetMapping("/api/users/me")
        String profile() {
            return "ok";
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtProvider jwtProvider;

    @MockitoBean
    private UserAccountRepository userAccountRepository;

    @Test
    @DisplayName("허용된 웹 주소의 사전 요청은 인증 없이 처리한다")
    void preflight_allowedOrigin_returns200() throws Exception {
        mockMvc.perform(options("/api/auth/login")
                        .header("Origin", "http://localhost:8081")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type,authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:8081"));
    }

    @Test
    @DisplayName("허용되지 않은 웹 주소의 사전 요청은 거절한다")
    void preflight_otherOrigin_returns403() throws Exception {
        mockMvc.perform(options("/api/auth/login")
                        .header("Origin", "https://unregistered.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    @DisplayName("보호된 API의 사전 요청도 인증 없이 처리한다")
    void preflight_protectedApi_returns200() throws Exception {
        mockMvc.perform(options("/api/users/me")
                        .header("Origin", "http://localhost:8081")
                        .header("Access-Control-Request-Method", "PATCH")
                        .header("Access-Control-Request-Headers", "authorization,content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:8081"));
    }

    @Test
    @DisplayName("인증이 필요한 API의 오류 응답에도 CORS 헤더를 포함한다")
    void protectedApi_withoutToken_returns401WithCorsHeader() throws Exception {
        mockMvc.perform(get("/api/users/me").header("Origin", "http://localhost:8081"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:8081"));
    }
}
