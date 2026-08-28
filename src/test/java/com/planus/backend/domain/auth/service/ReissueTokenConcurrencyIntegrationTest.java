package com.planus.backend.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.planus.backend.TestcontainersConfig;
import com.planus.backend.domain.auth.dto.LoginResponse;
import com.planus.backend.domain.auth.dto.ReissueRequest;
import com.planus.backend.domain.user.entity.AuthProvider;
import com.planus.backend.domain.user.entity.UserAccount;
import com.planus.backend.domain.user.repository.UserAccountRepository;
import com.planus.backend.global.apiPayload.code.GeneralErrorCode;
import com.planus.backend.global.apiPayload.exception.GeneralException;
import com.planus.backend.global.security.JwtProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class ReissueTokenConcurrencyIntegrationTest {

    @MockitoSpyBean
    private AuthService authService;

    @Autowired
    private JwtProvider jwtProvider;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @AfterEach
    void tearDown() {
        userAccountRepository.deleteAll();
    }

    @Test
    @Disabled("Refresh Token의 동일 초 재발급 고유성 보장 구현 전까지 비활성화")
    @DisplayName("동일 리프레시 토큰으로 동시 재발급 요청이 오면 하나만 성공하고 나머지는 INVALID_TOKEN이 발생한다")
    void concurrentReissue_onlyOneSucceeds() throws InterruptedException {
        // Given: 사용자 생성 후 리프레시 토큰 해시 저장
        UserAccount user = userAccountRepository.save(UserAccount.builder()
                .email("concurrent@example.com")
                .password("$2a$10$dummyhashfortest000000000000000000000000000000000000000")
                .provider(AuthProvider.LOCAL)
                .build());

        String refreshToken = jwtProvider.generateRefreshToken(user.getId());
        user.updateRefreshToken(jwtProvider.hashToken(refreshToken));
        userAccountRepository.save(user);

        ReissueRequest request = new ReissueRequest(refreshToken);

        // 두 스레드가 동시에 reissue()에 진입하도록 barrier 설정
        CyclicBarrier barrier = new CyclicBarrier(2);
        doAnswer(invocation -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return invocation.callRealMethod();
                })
                .when(authService)
                .reissue(any(ReissueRequest.class));

        // When: 두 스레드가 동시에 동일한 리프레시 토큰으로 재발급 요청
        CountDownLatch startLatch = new CountDownLatch(1);
        List<LoginResponse> successes = new ArrayList<>();
        List<Exception> errors = new ArrayList<>();

        Runnable task = () -> {
            try {
                startLatch.await();
                LoginResponse response = authService.reissue(request);
                synchronized (successes) {
                    successes.add(response);
                }
            } catch (Exception e) {
                synchronized (errors) {
                    errors.add(e);
                }
            }
        };

        Thread t1 = new Thread(task);
        Thread t2 = new Thread(task);
        t1.start();
        t2.start();
        startLatch.countDown();
        t1.join(10_000);
        t2.join(10_000);

        // Then: 정확히 하나만 성공하고 나머지는 INVALID_TOKEN
        assertThat(successes).hasSize(1);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).isInstanceOf(GeneralException.class).satisfies(ex -> assertThat(
                        ((GeneralException) ex).getErrorCode())
                .isEqualTo(GeneralErrorCode.INVALID_TOKEN));
    }
}
