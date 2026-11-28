package com.planus.backend.domain.aifeedback.llm;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpStatusCodeException;

/**
 * 외부 LLM 호출의 장애 대응 (#124): 오류 분류, 일시 장애 재시도, 요청 전체 시간 상한, 서킷 브레이커.
 *
 * <p>오류 분류
 * <ul>
 *   <li>일시 장애(429·529·5xx·연결/읽기 실패): 백오프 후 제한된 횟수만 재시도한다.
 *   <li>영구 장애(400 크레딧 소진·401 키 오류 등 그 밖의 4xx): 재시도하지 않고 서킷을 바로 연다 — 다음 사용자도 같은 이유로 실패한다.
 *   <li>전체 시간 초과: 읽기 타임아웃이 걸리지 않는 정지(본문 수신 중 멈춤 등)까지 잡는 호출 단위 상한.
 * </ul>
 *
 * <p>서킷이 열려 있는 동안은 호출 없이 즉시 {@link LlmUnavailableException}을 던져 {@link FeedbackRenderer}가 템플릿으로 폴백하게 한다.
 * 쿨다운이 지나면 호출 하나만 통과시켜(반열림) 성공하면 닫고 실패하면 다시 연다.
 */
public class LlmCallGuard {

    private static final Logger log = LoggerFactory.getLogger(LlmCallGuard.class);

    /** 실패 원인 분류. */
    public enum Kind {
        TRANSIENT,
        PERMANENT,
        TIMEOUT,
        CIRCUIT_OPEN
    }

    /** 호출이 실패했거나 건너뛰어졌음을 알린다. */
    public static class LlmUnavailableException extends RuntimeException {
        private final Kind kind;

        public LlmUnavailableException(Kind kind, String message, Throwable cause) {
            super(message, cause);
            this.kind = kind;
        }

        public Kind kind() {
            return kind;
        }
    }

    /** 설정값. 테스트에서 시간을 줄이려고 분리했다. */
    public record Settings(
            int maxRetries,
            Duration initialBackoff,
            Duration totalTimeout,
            int openAfterConsecutiveFailures,
            Duration openDuration) {
        public static Settings defaults() {
            return new Settings(2, Duration.ofSeconds(1), Duration.ofSeconds(60), 5, Duration.ofMinutes(5));
        }

        /** 장애 대응 없이 한 번만 호출하는 설정 — 개선 전 동작과 같다(측정 기준선). */
        public static Settings none(Duration totalTimeout) {
            return new Settings(0, Duration.ZERO, totalTimeout, Integer.MAX_VALUE, Duration.ZERO);
        }
    }

    /** 호출 통계. 배치가 끝날 때 {@link #snapshotAndReset()}로 가져가 로그에 남긴다. */
    public record Stats(
            long success,
            long transientFail,
            long permanentFail,
            long timeout,
            long circuitSkipped,
            long totalLatencyMs) {
        public long attempts() {
            return success + transientFail + permanentFail + timeout;
        }

        public String summary() {
            return String.format(
                    "LLM 호출 성공=%d 일시장애=%d 영구장애=%d 시간초과=%d 서킷건너뜀=%d 평균지연=%dms",
                    success,
                    transientFail,
                    permanentFail,
                    timeout,
                    circuitSkipped,
                    attempts() == 0 ? 0 : totalLatencyMs / attempts());
        }
    }

    private final Settings settings;
    private final LongSupplier nowMillis;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicLong openUntil = new AtomicLong(0);
    private final AtomicLong probeInFlightUntil = new AtomicLong(0);

    private final AtomicLong success = new AtomicLong();
    private final AtomicLong transientFail = new AtomicLong();
    private final AtomicLong permanentFail = new AtomicLong();
    private final AtomicLong timeout = new AtomicLong();
    private final AtomicLong circuitSkipped = new AtomicLong();
    private final AtomicLong latencyMs = new AtomicLong();

    public LlmCallGuard(Settings settings) {
        this(settings, System::currentTimeMillis);
    }

    LlmCallGuard(Settings settings, LongSupplier nowMillis) {
        this.settings = settings;
        this.nowMillis = nowMillis;
    }

    /** 오류 분류. HTTP 응답 코드가 있으면 코드로, 없으면(연결·읽기 실패) 일시 장애로 본다. */
    static Kind classify(Throwable t) {
        if (t instanceof HttpStatusCodeException h) {
            int s = h.getStatusCode().value();
            if (s == 429 || s == 529 || s >= 500) return Kind.TRANSIENT;
            return Kind.PERMANENT;
        }
        return Kind.TRANSIENT;
    }

    public <T> T call(Callable<T> action) {
        long start = nowMillis.getAsLong();
        gate(start);
        long deadline = start + settings.totalTimeout().toMillis();
        Throwable last = null;
        for (int attempt = 0; attempt <= settings.maxRetries(); attempt++) {
            long callStart = nowMillis.getAsLong();
            long remaining = deadline - callStart;
            if (remaining <= 0) break;
            Future<T> future = executor.submit(action);
            try {
                T result = future.get(remaining, TimeUnit.MILLISECONDS);
                record(Kind.TRANSIENT, callStart, true);
                consecutiveFailures.set(0);
                openUntil.set(0);
                probeInFlightUntil.set(0);
                return result;
            } catch (TimeoutException e) {
                future.cancel(true);
                record(Kind.TIMEOUT, callStart, false);
                tripIfNeeded(Kind.TIMEOUT);
                throw new LlmUnavailableException(Kind.TIMEOUT, "LLM 호출 전체 시간 초과", e);
            } catch (InterruptedException e) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                throw new LlmUnavailableException(Kind.TRANSIENT, "LLM 호출 중단", e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                Kind kind = classify(cause);
                record(kind, callStart, false);
                last = cause;
                if (kind == Kind.PERMANENT) {
                    tripIfNeeded(kind);
                    throw new LlmUnavailableException(kind, "LLM 영구 장애: " + cause.getMessage(), cause);
                }
                if (attempt < settings.maxRetries()) {
                    long backoff = settings.initialBackoff().toMillis() << attempt;
                    if (nowMillis.getAsLong() + backoff >= deadline) break;
                    log.warn(
                            "LLM 일시 장애 — {}ms 뒤 재시도 {}/{}: {}",
                            backoff,
                            attempt + 1,
                            settings.maxRetries(),
                            cause.getMessage());
                    sleep(backoff);
                }
            }
        }
        tripIfNeeded(Kind.TRANSIENT);
        throw new LlmUnavailableException(
                Kind.TRANSIENT, "LLM 호출 실패" + (last != null ? ": " + last.getMessage() : ""), last);
    }

    /** 서킷이 열려 있으면 호출 없이 거절한다. 쿨다운이 지나면 호출 하나만 통과시킨다. */
    private void gate(long now) {
        long until = openUntil.get();
        if (until == 0) return;
        if (now < until) {
            circuitSkipped.incrementAndGet();
            throw new LlmUnavailableException(Kind.CIRCUIT_OPEN, "LLM 서킷 열림 — 호출 생략", null);
        }
        // 반열림: 시험 호출 하나만 통과, 그 사이 다른 호출은 계속 거절한다
        long probeUntil = now + settings.totalTimeout().toMillis();
        long prev = probeInFlightUntil.get();
        if (prev > now || !probeInFlightUntil.compareAndSet(prev, probeUntil)) {
            circuitSkipped.incrementAndGet();
            throw new LlmUnavailableException(Kind.CIRCUIT_OPEN, "LLM 서킷 시험 호출 진행 중 — 호출 생략", null);
        }
    }

    private void tripIfNeeded(Kind kind) {
        int n = consecutiveFailures.incrementAndGet();
        if (settings.openDuration().isZero()) return; // 서킷 비활성(기준선 측정용)
        boolean permanent = kind == Kind.PERMANENT;
        if (permanent || n >= settings.openAfterConsecutiveFailures()) {
            openUntil.set(nowMillis.getAsLong() + settings.openDuration().toMillis());
            probeInFlightUntil.set(0);
            log.error(
                    "LLM 서킷 열림 ({}) — {}초 동안 호출 없이 템플릿 사용",
                    permanent ? "영구 장애" : "연속 실패 " + n + "회",
                    settings.openDuration().toSeconds());
        }
    }

    private void record(Kind kind, long callStart, boolean ok) {
        latencyMs.addAndGet(nowMillis.getAsLong() - callStart);
        if (ok) success.incrementAndGet();
        else if (kind == Kind.PERMANENT) permanentFail.incrementAndGet();
        else if (kind == Kind.TIMEOUT) timeout.incrementAndGet();
        else transientFail.incrementAndGet();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 지금까지의 통계를 가져가고 0으로 되돌린다. */
    public Stats snapshotAndReset() {
        return new Stats(
                success.getAndSet(0),
                transientFail.getAndSet(0),
                permanentFail.getAndSet(0),
                timeout.getAndSet(0),
                circuitSkipped.getAndSet(0),
                latencyMs.getAndSet(0));
    }
}
