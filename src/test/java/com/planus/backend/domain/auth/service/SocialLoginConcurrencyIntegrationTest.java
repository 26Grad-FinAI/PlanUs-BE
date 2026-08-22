package com.planus.backend.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;

import com.planus.backend.TestcontainersConfig;
import com.planus.backend.domain.auth.dto.SocialLoginRequest;
import com.planus.backend.domain.user.UserAccountPersister;
import com.planus.backend.domain.user.entity.UserAccount;
import com.planus.backend.domain.user.repository.UserAccountRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
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
class SocialLoginConcurrencyIntegrationTest {

    @MockitoSpyBean
    private KakaoOAuthService kakaoOAuthService;

    @MockitoSpyBean
    private UserAccountPersister userAccountPersister;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @AfterEach
    void tearDown() {
        userAccountRepository.deleteAll();
    }

    @Test
    @DisplayName("동일 Kakao 계정으로 동시 가입 요청이 오면 UnexpectedRollbackException 없이 둘 다 성공하고 refresh token이 저장된다")
    void concurrentSignup_bothRequestsSucceed_refreshTokenPersisted() throws InterruptedException {
        KakaoOAuthService.KakaoUserInfo userInfo = new KakaoOAuthService.KakaoUserInfo(
                123456789L,
                new KakaoOAuthService.KakaoAccount(
                        "concurrent@kakao.com", true, true, new KakaoOAuthService.KakaoProfile("홍길동")));

        doReturn("kakao-token").when(kakaoOAuthService).fetchAccessToken(anyString(), anyString());
        doReturn(userInfo).when(kakaoOAuthService).fetchUserInfo("kakao-token");

        // 두 스레드가 동시에 saveAndFlush에 진입하도록 barrier 설정
        CyclicBarrier barrier = new CyclicBarrier(2);
        doAnswer(invocation -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return invocation.callRealMethod();
                })
                .when(userAccountPersister)
                .saveAndFlush(any(UserAccount.class));

        SocialLoginRequest request = new SocialLoginRequest("auth-code", "http://localhost/callback");

        CountDownLatch startLatch = new CountDownLatch(1);
        List<Exception> errors = new ArrayList<>();

        Thread t1 = new Thread(() -> {
            try {
                startLatch.await();
                kakaoOAuthService.login(request);
            } catch (Exception e) {
                synchronized (errors) {
                    errors.add(e);
                }
            }
        });

        Thread t2 = new Thread(() -> {
            try {
                startLatch.await();
                kakaoOAuthService.login(request);
            } catch (Exception e) {
                synchronized (errors) {
                    errors.add(e);
                }
            }
        });

        t1.start();
        t2.start();
        startLatch.countDown();
        t1.join(10_000);
        t2.join(10_000);

        assertThat(errors).isEmpty();
        UserAccount saved =
                userAccountRepository.findByEmail("concurrent@kakao.com").orElseThrow();
        assertThat(saved.getRefreshToken()).as("refresh token이 DB에 저장되어야 한다").isNotNull();
    }
}
