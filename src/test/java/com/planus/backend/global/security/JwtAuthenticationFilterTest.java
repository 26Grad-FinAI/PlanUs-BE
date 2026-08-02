package com.planus.backend.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planus.backend.global.apiPayload.code.GeneralErrorCode;
import com.planus.backend.global.apiPayload.exception.GeneralException;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

class JwtAuthenticationFilterTest {

    private JwtProvider jwtProvider;
    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        jwtProvider = mock(JwtProvider.class);
        filter = new JwtAuthenticationFilter(jwtProvider, new ObjectMapper());
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletRequest requestWithToken(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }

    @Nested
    @DisplayName("유효한 토큰")
    class ValidToken {

        @Test
        @DisplayName("SecurityContext에 userId가 등록되고 다음 필터로 넘어간다")
        void validToken_setsAuthenticationAndPassesThrough() throws Exception {
            MockHttpServletRequest request = requestWithToken("valid-token");
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain filterChain = mock(FilterChain.class);

            when(jwtProvider.validateToken("valid-token")).thenReturn(true);
            when(jwtProvider.getUserId("valid-token")).thenReturn(1L);

            filter.doFilterInternal(request, response, filterChain);

            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
            assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal())
                    .isEqualTo(1L);
            verify(filterChain).doFilter(request, response);
        }
    }

    @Nested
    @DisplayName("유효하지 않은 토큰")
    class InvalidToken {

        @Test
        @DisplayName("만료된 토큰이면 401과 AUTH_401_004를 반환하고 필터 체인을 중단한다")
        void expiredToken_returns401WithExpiredCode() throws Exception {
            MockHttpServletRequest request = requestWithToken("expired-token");
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain filterChain = mock(FilterChain.class);

            when(jwtProvider.validateToken("expired-token"))
                    .thenThrow(new GeneralException(GeneralErrorCode.EXPIRED_TOKEN));

            filter.doFilterInternal(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(response.getContentAsString()).contains("AUTH_401_004");
            verify(filterChain, never()).doFilter(request, response);
        }

        @Test
        @DisplayName("서명이 잘못된 토큰이면 401과 AUTH_401_003을 반환하고 필터 체인을 중단한다")
        void invalidToken_returns401WithInvalidCode() throws Exception {
            MockHttpServletRequest request = requestWithToken("invalid-token");
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain filterChain = mock(FilterChain.class);

            when(jwtProvider.validateToken("invalid-token"))
                    .thenThrow(new GeneralException(GeneralErrorCode.INVALID_TOKEN));

            filter.doFilterInternal(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(response.getContentAsString()).contains("AUTH_401_003");
            verify(filterChain, never()).doFilter(request, response);
        }
    }

    @Nested
    @DisplayName("토큰 없음")
    class NoToken {

        @Test
        @DisplayName("Authorization 헤더가 없으면 SecurityContext가 비어있고 다음 필터로 넘어간다")
        void noToken_emptySecurityContextAndPassesThrough() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest();
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain filterChain = mock(FilterChain.class);

            filter.doFilterInternal(request, response, filterChain);

            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
            verify(filterChain).doFilter(request, response);
        }
    }
}
