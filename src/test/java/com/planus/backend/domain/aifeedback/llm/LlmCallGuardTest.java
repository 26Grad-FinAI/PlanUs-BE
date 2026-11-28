package com.planus.backend.domain.aifeedback.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.planus.backend.domain.aifeedback.llm.LlmCallGuard.Kind;
import com.planus.backend.domain.aifeedback.llm.LlmCallGuard.LlmUnavailableException;
import com.planus.backend.domain.aifeedback.llm.LlmCallGuard.Settings;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

class LlmCallGuardTest {

    private static Settings fast(int retries, int openAfter) {
        return new Settings(retries, Duration.ofMillis(1), Duration.ofSeconds(5), openAfter, Duration.ofMinutes(5));
    }

    private static HttpServerErrorException status5xx(HttpStatus s) {
        return HttpServerErrorException.create(s, s.name(), null, new byte[0], null);
    }

    private static HttpClientErrorException status4xx(HttpStatus s) {
        return HttpClientErrorException.create(s, s.name(), null, new byte[0], null);
    }

    @Test
    @DisplayName("일시 장애(529)는 재시도하다 성공하면 결과를 돌려준다")
    void transient_retriesThenSucceeds() {
        LlmCallGuard guard = new LlmCallGuard(fast(2, 5));
        AtomicInteger calls = new AtomicInteger();

        String r = guard.call(() -> {
            if (calls.incrementAndGet() < 3) throw status5xx(HttpStatus.SERVICE_UNAVAILABLE);
            return "ok";
        });

        assertThat(r).isEqualTo("ok");
        assertThat(calls).hasValue(3);
        LlmCallGuard.Stats st = guard.snapshotAndReset();
        assertThat(st.success()).isEqualTo(1);
        assertThat(st.transientFail()).isEqualTo(2);
    }

    @Test
    @DisplayName("재시도를 모두 쓰면 일시 장애로 던진다 — 호출 횟수는 1 + 재시도")
    void transient_exhaustsRetries() {
        LlmCallGuard guard = new LlmCallGuard(fast(2, 100));
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> guard.call(() -> {
                    calls.incrementAndGet();
                    throw status5xx(HttpStatus.INTERNAL_SERVER_ERROR);
                }))
                .isInstanceOfSatisfying(
                        LlmUnavailableException.class, e -> assertThat(e.kind()).isEqualTo(Kind.TRANSIENT));
        assertThat(calls).hasValue(3);
    }

    @Test
    @DisplayName("429는 일시 장애라 재시도한다")
    void tooManyRequests_isTransient() {
        assertThat(LlmCallGuard.classify(status4xx(HttpStatus.TOO_MANY_REQUESTS)))
                .isEqualTo(Kind.TRANSIENT);
    }

    @Test
    @DisplayName("400(크레딧 소진)·401(키 오류)은 영구 장애다")
    void clientErrors_arePermanent() {
        assertThat(LlmCallGuard.classify(status4xx(HttpStatus.BAD_REQUEST))).isEqualTo(Kind.PERMANENT);
        assertThat(LlmCallGuard.classify(status4xx(HttpStatus.UNAUTHORIZED))).isEqualTo(Kind.PERMANENT);
    }

    @Test
    @DisplayName("연결·읽기 실패처럼 HTTP 응답이 없는 예외는 일시 장애다")
    void ioError_isTransient() {
        assertThat(LlmCallGuard.classify(new RuntimeException("connection reset")))
                .isEqualTo(Kind.TRANSIENT);
    }

    @Test
    @DisplayName("영구 장애는 재시도 없이 한 번만 호출하고 서킷을 연다 — 다음 호출은 호출 자체를 하지 않는다")
    void permanent_noRetry_opensCircuit() {
        LlmCallGuard guard = new LlmCallGuard(fast(2, 5));
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> guard.call(() -> {
                    calls.incrementAndGet();
                    throw status4xx(HttpStatus.BAD_REQUEST);
                }))
                .isInstanceOfSatisfying(
                        LlmUnavailableException.class, e -> assertThat(e.kind()).isEqualTo(Kind.PERMANENT));
        assertThat(calls).hasValue(1);

        assertThatThrownBy(() -> guard.call(() -> {
                    calls.incrementAndGet();
                    return "x";
                }))
                .isInstanceOfSatisfying(
                        LlmUnavailableException.class, e -> assertThat(e.kind()).isEqualTo(Kind.CIRCUIT_OPEN));
        assertThat(calls).as("서킷이 열린 뒤에는 호출하지 않는다").hasValue(1);
        assertThat(guard.snapshotAndReset().circuitSkipped()).isEqualTo(1);
    }

    @Test
    @DisplayName("일시 장애가 연속으로 임계 횟수에 이르면 서킷이 열린다")
    void transient_consecutiveFailures_openCircuit() {
        LlmCallGuard guard = new LlmCallGuard(fast(0, 3));
        AtomicInteger calls = new AtomicInteger();
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> guard.call(() -> {
                        calls.incrementAndGet();
                        throw status5xx(HttpStatus.INTERNAL_SERVER_ERROR);
                    }))
                    .isInstanceOf(LlmUnavailableException.class);
        }
        assertThatThrownBy(() -> guard.call(() -> {
                    calls.incrementAndGet();
                    return "x";
                }))
                .isInstanceOfSatisfying(
                        LlmUnavailableException.class, e -> assertThat(e.kind()).isEqualTo(Kind.CIRCUIT_OPEN));
        assertThat(calls).hasValue(3);
    }

    @Test
    @DisplayName("성공하면 연속 실패 횟수가 0으로 돌아가 서킷이 열리지 않는다")
    void success_resetsConsecutiveFailures() {
        LlmCallGuard guard = new LlmCallGuard(fast(0, 3));
        for (int round = 0; round < 3; round++) {
            for (int i = 0; i < 2; i++) {
                assertThatThrownBy(() -> guard.call(() -> {
                            throw status5xx(HttpStatus.INTERNAL_SERVER_ERROR);
                        }))
                        .isInstanceOf(LlmUnavailableException.class);
            }
            assertThat(guard.call(() -> "ok")).isEqualTo("ok");
        }
    }

    @Test
    @DisplayName("쿨다운이 지나면 호출 하나만 통과시키고, 성공하면 서킷이 닫힌다")
    void halfOpen_probeSucceeds_closesCircuit() {
        AtomicLong now = new AtomicLong(1_000);
        LlmCallGuard guard = new LlmCallGuard(fast(0, 1), now::get);
        assertThatThrownBy(() -> guard.call(() -> {
                    throw status4xx(HttpStatus.UNAUTHORIZED);
                }))
                .isInstanceOf(LlmUnavailableException.class);

        now.addAndGet(Duration.ofMinutes(5).toMillis() + 1);
        assertThat(guard.call(() -> "recovered")).isEqualTo("recovered");
        assertThat(guard.call(() -> "again")).as("서킷이 닫혔다").isEqualTo("again");
    }

    @Test
    @DisplayName("시험 호출이 실패하면 서킷이 다시 열린다")
    void halfOpen_probeFails_reopens() {
        AtomicLong now = new AtomicLong(1_000);
        LlmCallGuard guard = new LlmCallGuard(fast(0, 1), now::get);
        assertThatThrownBy(() -> guard.call(() -> {
                    throw status4xx(HttpStatus.UNAUTHORIZED);
                }))
                .isInstanceOf(LlmUnavailableException.class);

        now.addAndGet(Duration.ofMinutes(5).toMillis() + 1);
        assertThatThrownBy(() -> guard.call(() -> {
                    throw status4xx(HttpStatus.UNAUTHORIZED);
                }))
                .isInstanceOfSatisfying(
                        LlmUnavailableException.class, e -> assertThat(e.kind()).isEqualTo(Kind.PERMANENT));
        assertThatThrownBy(() -> guard.call(() -> "x"))
                .isInstanceOfSatisfying(
                        LlmUnavailableException.class, e -> assertThat(e.kind()).isEqualTo(Kind.CIRCUIT_OPEN));
    }

    @Test
    @DisplayName("멈춘 호출은 전체 시간 상한에서 끊고 중단시킨다")
    void hang_isCutAtTotalTimeout() {
        LlmCallGuard guard =
                new LlmCallGuard(new Settings(0, Duration.ZERO, Duration.ofMillis(200), 100, Duration.ofMinutes(5)));
        AtomicInteger interrupted = new AtomicInteger();
        long start = System.currentTimeMillis();

        assertThatThrownBy(() -> guard.call(() -> {
                    try {
                        Thread.sleep(60_000);
                    } catch (InterruptedException e) {
                        interrupted.incrementAndGet();
                        throw e;
                    }
                    return "late";
                }))
                .isInstanceOfSatisfying(
                        LlmUnavailableException.class, e -> assertThat(e.kind()).isEqualTo(Kind.TIMEOUT));

        assertThat(System.currentTimeMillis() - start).isLessThan(2_000);
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2)).until(() -> interrupted.get() == 1);
    }

    @Test
    @DisplayName("재시도 대기까지 합쳐 전체 시간 상한을 넘기지 않는다")
    void retries_respectTotalDeadline() {
        LlmCallGuard guard = new LlmCallGuard(
                new Settings(5, Duration.ofMillis(300), Duration.ofMillis(500), 100, Duration.ofMinutes(5)));
        AtomicInteger calls = new AtomicInteger();
        long start = System.currentTimeMillis();

        assertThatThrownBy(() -> guard.call(() -> {
                    calls.incrementAndGet();
                    throw status5xx(HttpStatus.INTERNAL_SERVER_ERROR);
                }))
                .isInstanceOf(LlmUnavailableException.class);

        assertThat(System.currentTimeMillis() - start).isLessThan(1_500);
        assertThat(calls.get()).isLessThan(6);
    }

    @Test
    @DisplayName("통계는 가져가면 0으로 돌아간다")
    void stats_resetAfterSnapshot() {
        LlmCallGuard guard = new LlmCallGuard(fast(0, 100));
        guard.call(() -> "ok");
        assertThat(guard.snapshotAndReset().success()).isEqualTo(1);
        assertThat(guard.snapshotAndReset().attempts()).isZero();
    }
}
