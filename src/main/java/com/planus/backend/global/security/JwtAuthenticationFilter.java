package com.planus.backend.global.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planus.backend.domain.user.repository.UserAccountRepository;
import com.planus.backend.global.apiPayload.ApiResponse;
import com.planus.backend.global.apiPayload.code.BaseErrorCode;
import com.planus.backend.global.apiPayload.code.GeneralErrorCode;
import com.planus.backend.global.apiPayload.exception.GeneralException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** 요청마다 JWT 액세스 토큰을 검증하고 SecurityContext에 인증 정보를 설정하는 필터. */
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtProvider jwtProvider;
    private final ObjectMapper objectMapper;
    private final UserAccountRepository userAccountRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String token = resolveToken(request);

        if (token != null) {
            try {
                Long userId = jwtProvider.parseUserId(token);
                // 탈퇴된 사용자의 토큰을 즉시 무효화하기 위해 DB에서 유저 존재 여부를 확인한다.
                if (!userAccountRepository.existsById(userId)) {
                    sendErrorResponse(response, GeneralErrorCode.UNAUTHORIZED);
                    return;
                }
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(userId, null, List.of());
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (GeneralException e) {
                sendErrorResponse(response, e.getErrorCode());
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {
        String bearer = request.getHeader("Authorization");
        if (bearer != null && bearer.startsWith(BEARER_PREFIX)) {
            return bearer.substring(BEARER_PREFIX.length());
        }
        return null;
    }

    /**
     * 필터 계층에서 발생한 JWT 예외를 클라이언트에게 JSON으로 직접 응답한다.
     *
     * <p>필터는 DispatcherServlet 앞에서 동작하므로 {@code @ControllerAdvice}가 예외를 처리하지 못한다.
     * 때문에 {@link HttpServletResponse}에 직접 JSON을 작성한다.</p>
     */
    private void sendErrorResponse(HttpServletResponse response, BaseErrorCode errorCode) throws IOException {
        response.setStatus(errorCode.getHttpStatus().value());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.onFailure(errorCode, null)));
    }
}
