package com.planus.backend.domain.aifeedback.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 가짜 LLM 서버로 장애 시나리오를 재현해, 장애 대응 전(재시도·서킷·전체 시간 상한 없음)과 후를 같은 입력으로 비교한다 (#124).
 * API 키가 필요 없다.
 */
class LlmFailureScenarioTest {

    private static final int USERS = 20;
    private static final String OK_BODY = "{\"content\":[{\"type\":\"text\",\"text\":\"안녕\"}]}";

    private HttpServer server;
    private final AtomicInteger upstreamCalls = new AtomicInteger();

    /** 호출 번호(1부터)로 응답을 정한다. 응답 코드 0 = 헤더만 보내고 본문을 멈춘다(읽기 타임아웃이 걸리지 않는 정지). */
    private void serve(IntFunction<Integer> statusByCall) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/v1/messages", ex -> {
            int n = upstreamCalls.incrementAndGet();
            ex.getRequestBody().readAllBytes();
            int status = statusByCall.apply(n);
            byte[] body = (status == 200
                            ? OK_BODY
                            : "{\"type\":\"error\",\"error\":{\"type\":\"x\",\"message\":\"fail\"}}")
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            if (status == 0) {
                ex.sendResponseHeaders(200, body.length);
                ex.getResponseBody().flush();
                try {
                    Thread.sleep(120_000);
                } catch (InterruptedException ignored) {
                }
                return;
            }
            ex.sendResponseHeaders(status, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
    }

    @BeforeEach
    void reset() {
        upstreamCalls.set(0);
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private AnthropicLlmClient client(boolean resilient, long readTimeoutSec, long totalTimeoutSec) {
        AnthropicLlmClient c = new AnthropicLlmClient();
        ReflectionTestUtils.setField(c, "apiKey", "test-key");
        ReflectionTestUtils.setField(c, "model", "m");
        ReflectionTestUtils.setField(c, "maxTokens", 10);
        ReflectionTestUtils.setField(
                c, "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
        ReflectionTestUtils.setField(c, "readTimeoutSeconds", readTimeoutSec);
        ReflectionTestUtils.setField(c, "retryBackoffMillis", 20L);
        if (resilient) {
            ReflectionTestUtils.setField(c, "totalTimeoutSeconds", totalTimeoutSec);
            ReflectionTestUtils.setField(c, "maxRetries", 2);
            ReflectionTestUtils.setField(c, "circuitOpenAfter", 5);
            ReflectionTestUtils.setField(c, "circuitOpenSeconds", 300L);
        } else { // 개선 전과 같은 동작: 한 번만 호출, 서킷 없음, 전체 상한 없음
            ReflectionTestUtils.setField(c, "totalTimeoutSeconds", 3600L);
            ReflectionTestUtils.setField(c, "maxRetries", 0);
            ReflectionTestUtils.setField(c, "circuitOpenAfter", Integer.MAX_VALUE);
            ReflectionTestUtils.setField(c, "circuitOpenSeconds", 0L);
        }
        c.init();
        return c;
    }

    /** 사용자 {@link #USERS}명을 순차로 처리한다. 반환: [성공 수, 실패(템플릿 폴백) 수, 걸린 시간 ms]. */
    private long[] runBatch(AnthropicLlmClient c) {
        long start = System.currentTimeMillis();
        int ok = 0, fail = 0;
        for (int i = 0; i < USERS; i++) {
            try {
                c.complete("s", "u");
                ok++;
            } catch (Exception e) {
                fail++;
            }
        }
        return new long[] {ok, fail, System.currentTimeMillis() - start};
    }

    private void report(String name, String label, long[] r) {
        System.out.printf(
                "[#124 %s] %s: 성공 %d, 폴백 %d, 상류 호출 %d, 배치 %dms%n", name, label, r[0], r[1], upstreamCalls.get(), r[2]);
    }

    @Test
    @DisplayName("크레딧 소진(400): 개선 전에는 사용자마다 호출하지만, 후에는 한 번만 호출하고 나머지는 건너뛴다")
    void creditExhausted() throws Exception {
        serve(n -> 400);
        long[] before = runBatch(client(false, 5, 10));
        report("크레딧400", "개선 전", before);
        int beforeCalls = upstreamCalls.getAndSet(0);

        long[] after = runBatch(client(true, 5, 10));
        report("크레딧400", "개선 후", after);

        assertThat(beforeCalls).isEqualTo(USERS);
        assertThat(upstreamCalls.get()).as("영구 장애는 첫 호출 뒤 서킷이 열려 더 부르지 않는다").isEqualTo(1);
        assertThat(after[1]).isEqualTo(USERS);
    }

    @Test
    @DisplayName("일시 과부하(529): 앞 두 호출만 실패하면 개선 후에는 재시도로 사용자 손실이 없다")
    void overloadedBriefly() throws Exception {
        serve(n -> n <= 2 ? 529 : 200);
        long[] before = runBatch(client(false, 5, 10));
        report("과부하529", "개선 전", before);
        upstreamCalls.set(0);

        long[] after = runBatch(client(true, 5, 10));
        report("과부하529", "개선 후", after);

        assertThat(before[1]).as("개선 전: 앞 사용자 2명이 폴백").isEqualTo(2);
        assertThat(after[1]).as("개선 후: 첫 사용자의 재시도가 성공해 폴백 없음").isZero();
    }

    @Test
    @DisplayName("서버 계속 장애(500): 개선 후에는 연속 실패 뒤 서킷이 열려 상류 호출이 제한된다")
    void serverDown() throws Exception {
        serve(n -> 500);
        long[] before = runBatch(client(false, 5, 10));
        report("서버500", "개선 전", before);
        int beforeCalls = upstreamCalls.getAndSet(0);

        long[] after = runBatch(client(true, 5, 10));
        report("서버500", "개선 후", after);

        assertThat(beforeCalls).isEqualTo(USERS);
        assertThat(upstreamCalls.get())
                .as("연속 실패 5회 뒤 서킷이 열린다(재시도 포함해도 20보다 적다)")
                .isLessThan(USERS);
    }

    @Test
    @DisplayName("본문이 멈춘 응답: 읽기 타임아웃과 별개로 전체 시간 상한이 호출을 끊는다")
    void bodyStall() throws Exception {
        serve(n -> 0);
        AnthropicLlmClient c = client(true, 30, 1);
        long start = System.currentTimeMillis();
        try {
            c.complete("s", "u");
        } catch (Exception expected) {
            // 템플릿 폴백 대상
        }
        long elapsed = System.currentTimeMillis() - start;
        System.out.printf("[#124 본문정지] 개선 후: 1건 %dms (전체 상한 1초)%n", elapsed);
        assertThat(elapsed).isLessThan(5_000);
    }

    @Test
    @DisplayName("재현 시도: 헤더만 오고 본문이 멈추면 개선 전 읽기 타임아웃(2초)이 걸리는가")
    void bodyStall_baselineReadTimeout() throws Exception {
        serve(n -> 0);
        AnthropicLlmClient c = client(false, 2, 10);
        long start = System.currentTimeMillis();
        try {
            c.complete("s", "u");
        } catch (Exception e) {
            System.out.println("[#124 본문정지] 개선 전 예외: " + e.getClass().getSimpleName());
        }
        long elapsed = System.currentTimeMillis() - start;
        System.out.printf("[#124 본문정지] 개선 전: 읽기 타임아웃 2초 설정에서 %dms 걸림%n", elapsed);
    }

    @Test
    @DisplayName("정상 응답은 그대로 텍스트를 돌려주고 통계에 성공으로 센다")
    void normal() throws Exception {
        serve(n -> 200);
        AnthropicLlmClient c = client(true, 5, 10);
        assertThat(c.complete("s", "u")).isEqualTo("안녕");
        LlmCallGuard.Stats st = c.snapshotStats();
        assertThat(st.success()).isEqualTo(1);
        assertThat(st.summary()).contains("성공=1");
    }
}
