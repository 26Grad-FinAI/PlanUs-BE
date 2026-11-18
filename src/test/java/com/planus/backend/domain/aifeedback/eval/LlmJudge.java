package com.planus.backend.domain.aifeedback.eval;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlockParam;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.planus.backend.domain.aifeedback.eval.BlindJudging.Csv;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LLM 판정기 (#140, 테스트 전용). 사람 판정 기준서({@code docs/ai-feedback/judging-rubric.md})를 시스템 프롬프트로 써서 판정 항목을 O/X로 판정한다.
 *
 * <p>버전 정보({@code _key/})는 읽지 않는다. 결과는 사람 판정 시트와 같은 형식의 CSV라 {@code BlindJudging score}·{@code agree}로 바로 비교된다.
 * 원본 응답은 JSONL로 남기고, 다시 실행하면 이미 판정한 항목은 호출하지 않는다 (중간에 멈춰도 비용이 다시 들지 않게).
 *
 * <pre>
 * ./gradlew judge -Pcmd=llm -Pdir=판정폴더 -Pout=LLM판정.csv [-Pids="J001;J002"] [-PjudgeModel=claude-opus-5-5] [-Peffort=medium]
 * </pre>
 *
 * <p>ANTHROPIC_API_KEY 환경변수가 필요하다.
 */
public final class LlmJudge {

    static final String DEFAULT_MODEL = "claude-opus-5-5";
    /** 판정기 지시문(systemPrompt의 고정 문구) 버전. 문구를 바꾸면 올려서 이전 판정을 재사용하지 않게 한다. */
    static final String JUDGE_PROMPT_VERSION = "1";

    static final List<String> ERROR_TYPES =
            List.of("숫자출처", "대상기준", "기간", "절약효과", "사실반대", "건수", "내부용어", "톤", "원인", "기타");
    /** 사실 오류 — 사용자가 숫자·사실을 잘못 알게 되는 유형 (#140 판정자 기준). 나머지 유형은 조언 품질. */
    static final Set<String> FACT_ERROR_TYPES = Set.of("숫자출처", "대상기준", "기간", "건수", "내부용어");

    /** 버전별 집계(score)에 넣을 지표. 지표마다 X/O 시트를 따로 만든다. */
    enum Metric {
        FACT("사실오류"),
        ADVICE("조언품질"),
        MISSING_WEEK_TOTAL("총지출누락");

        final String label;

        Metric(String label) {
            this.label = label;
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int CONCURRENCY = 4;

    private LlmJudge() {}

    /** 판정 1건. errors는 (유형, 응답 인용, 근거). */
    public record Verdict(
            String id,
            Boolean error,
            List<String> types,
            List<String> evidence,
            boolean ambiguous,
            String ambiguousReason,
            boolean missingWeekTotal) {}

    // ── 프롬프트·스키마 ──

    static String systemPrompt(String rubric) {
        return """
                너는 AI 소비 피드백 응답의 내용 오류를 판정하는 판정자다. 아래 판정 기준서를 사람 판정자와 똑같이 적용한다.

                - 근거는 사용자 메시지의 "분석 재료"뿐이다. 다른 지식으로 사실을 보충하지 않는다.
                - 분석 재료의 모든 줄을 확인한다. 카테고리 아래 "감정:", "거래처별:", "메모" 줄에도 근거가 있다 (예: 택시 2건이 후회로 태그됨).
                - 기준서의 "틀림(X) 유형"이 하나라도 있으면 X, 없으면 O. "틀림이 아닌 것 (O)"에 해당하면 X로 보지 않는다.
                - X이면 errors에 유형 코드, 응답 원문 인용, 분석 재료의 해당 값을 적는다. O이면 errors는 비운다.
                - 사용자가 문장을 자연스럽게 읽었을 때 X 유형에 해당하면 X로 판정한다. 다르게 읽을 여지가 있다는 이유만으로 O로 판정하지 않는다.
                - 기준서만으로 판단이 갈릴 수 있으면 ambiguous를 true로 하고 이유를 적는다. 그래도 verdict는 위 원칙대로 반드시 고른다.
                - missing_week_total: 응답 어디에도 이번 주 총지출(분석 재료의 "이번 주 총 변동지출", 반올림 허용)을 말하지 않으면 true. 카테고리 금액이나 전주 대비 증감만 말한 경우도 true. 오류 판정과는 별개다.
                - 기준서의 "판정 방법"·"집계" 절은 사람용 안내이므로 무시한다.

                <판정_기준서>
                """
                + rubric
                + "\n</판정_기준서>\n";
    }

    static String userPrompt(String response, String prompt) {
        return "## 분석 재료 (이 응답을 만든 프롬프트)\n\n```\n" + prompt + "\n```\n\n## 판정할 응답\n\n" + response + "\n";
    }

    static Map<String, Object> schema() {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("type", "object");
        error.put(
                "properties",
                Map.of(
                        "type", Map.of("type", "string", "enum", ERROR_TYPES),
                        "quote", Map.of("type", "string", "description", "응답 원문에서 틀린 부분 인용"),
                        "evidence", Map.of("type", "string", "description", "분석 재료의 해당 값과 왜 틀렸는지")));
        error.put("required", List.of("type", "quote", "evidence"));
        error.put("additionalProperties", false);

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("verdict", Map.of("type", "string", "enum", List.of("O", "X")));
        props.put("errors", Map.of("type", "array", "items", error));
        props.put("ambiguous", Map.of("type", "boolean"));
        props.put("ambiguous_reason", Map.of("type", "string"));
        props.put("missing_week_total", Map.of("type", "boolean"));

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        root.put("properties", props);
        root.put("required", List.of("verdict", "errors", "ambiguous", "ambiguous_reason", "missing_week_total"));
        root.put("additionalProperties", false);
        return root;
    }

    static MessageCreateParams request(String model, String effort, String system, String user) {
        JsonOutputFormat.Schema.Builder schema = JsonOutputFormat.Schema.builder();
        schema().forEach((k, v) -> schema.putAdditionalProperty(k, JsonValue.from(v)));
        return MessageCreateParams.builder()
                .model(model)
                .maxTokens(16000L)
                .systemOfTextBlockParams(List.of(TextBlockParam.builder()
                        .text(system)
                        .cacheControl(CacheControlEphemeral.builder().build()) // 기준서는 모든 호출에서 같음
                        .build()))
                .addUserMessage(user)
                .outputConfig(OutputConfig.builder()
                        .effort(OutputConfig.Effort.of(effort))
                        .format(JsonOutputFormat.builder()
                                .schema(schema.build())
                                .build())
                        .build())
                // 안전 분류기가 거절하면 서버가 다른 모델로 이어서 응답 (거절된 항목은 stop_reason으로 따로 기록)
                .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                .build();
    }

    /** 구조화 출력 JSON을 판정으로 바꾼다. verdict가 X인데 errors가 비면 그대로 X로 두고 근거 없음으로 표시한다. */
    static Verdict parse(String id, String json) throws IOException {
        JsonNode n = JSON.readTree(json);
        List<String> types = new ArrayList<>(), evidence = new ArrayList<>();
        for (JsonNode e : n.path("errors")) {
            types.add(e.path("type").asText());
            evidence.add(e.path("type").asText() + ": \"" + e.path("quote").asText() + "\" — "
                    + e.path("evidence").asText());
        }
        return new Verdict(
                id,
                "X".equals(n.path("verdict").asText()),
                List.copyOf(new LinkedHashSet<>(types)),
                evidence,
                n.path("ambiguous").asBoolean(),
                n.path("ambiguous_reason").asText(""),
                n.path("missing_week_total").asBoolean());
    }

    /** 판정 시트 한 줄. 메모 앞의 [애매]·[총지출없음] 태그로 사람이 다시 볼 항목을 걸러낸다. */
    static List<String> sheetRow(Verdict v, String kind, String response) {
        StringBuilder memo = new StringBuilder();
        if (v.ambiguous()) memo.append("[애매] ").append(v.ambiguousReason()).append(' ');
        if (v.missingWeekTotal()) memo.append("[총지출없음] ");
        memo.append(String.join(" / ", v.evidence()));
        String label = v.error() == null ? "" : v.error() ? "X" : "O";
        return List.of(
                v.id(),
                kind,
                response,
                label,
                String.join(", ", v.types()),
                memo.toString().strip());
    }

    /** 판정은 X인데 오류 유형이 없는 경우의 표시. 놓치지 않도록 사실 오류로 센다. */
    static final String UNTYPED = "미분류";

    /**
     * 지표 시트 한 줄: 해당 지표에 걸리면 X, 해당하지 않는 응답은 판정을 비워 집계에서 뺀다. 유형 열에는 그 지표의 유형만 쓴다. 응답 본문은 넣지 않는다.
     */
    static List<String> metricRow(Verdict v, Metric m, String kind) {
        boolean untyped = Boolean.TRUE.equals(v.error()) && v.types().isEmpty();
        List<String> types =
                switch (m) {
                    case FACT ->
                        untyped
                                ? List.of(UNTYPED)
                                : v.types().stream()
                                        .filter(FACT_ERROR_TYPES::contains)
                                        .toList();
                    case ADVICE ->
                        v.types().stream()
                                .filter(t -> !FACT_ERROR_TYPES.contains(t))
                                .toList();
                    case MISSING_WEEK_TOTAL -> List.of();
                };
        String label =
                switch (m) {
                    case FACT, ADVICE -> types.isEmpty() ? "O" : "X";
                    // 이번 주 총지출은 주간 피드백에만 해당 — 월말 응답은 집계에서 뺀다
                    case MISSING_WEEK_TOTAL -> kind.equals("주간") ? (v.missingWeekTotal() ? "X" : "O") : "";
                };
        return List.of(v.id(), kind, "", label, String.join(", ", types), "");
    }

    /** 판정 설정 지문: 이 값이 같은 기록만 재사용한다 (기준서·지시문·모델·effort 중 하나라도 바뀌면 다시 판정). */
    static Map<String, String> config(String model, String effort, String rubric) {
        return Map.of(
                "model", model,
                "effort", effort,
                "prompt_version", JUDGE_PROMPT_VERSION,
                "rubric_sha", BlindJudging.sha(rubric));
    }

    /** JSONL에서 같은 설정으로 끝난 판정만 읽는다. 잘린 줄(중단 중 쓰다 만 기록)은 건너뛴다. */
    static Map<String, Verdict> loadDone(Path raw, Map<String, String> config) throws IOException {
        Map<String, Verdict> done = new LinkedHashMap<>();
        if (!Files.exists(raw)) return done;
        int broken = 0, otherConfig = 0;
        for (String line : Files.readAllLines(raw, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            JsonNode n;
            try {
                n = JSON.readTree(line);
            } catch (IOException e) {
                broken++;
                continue;
            }
            if (!n.has("json")) continue;
            boolean same = config.entrySet().stream()
                    .allMatch(c -> c.getValue().equals(n.path(c.getKey()).asText(null)));
            if (!same) {
                otherConfig++;
                continue;
            }
            try {
                done.put(
                        n.get("id").asText(),
                        parse(n.get("id").asText(), n.get("json").asText()));
            } catch (IOException e) {
                broken++;
            }
        }
        if (broken > 0) System.out.printf("기록 중 읽을 수 없는 줄 %d개를 건너뜁니다 (다시 판정).%n", broken);
        if (otherConfig > 0) System.out.printf("판정 설정이 다른 이전 기록 %d개는 재사용하지 않습니다.%n", otherConfig);
        return done;
    }

    // ── 실행 ──

    public static void run(Path exportDir, Path outCsv, List<String> onlyIds, String model, String effort)
            throws Exception {
        String rubric = Files.readString(Path.of("docs/ai-feedback/judging-rubric.md"), StandardCharsets.UTF_8);
        String system = systemPrompt(rubric);

        List<List<String>> sheet = Csv.read(exportDir.resolve(BlindJudging.FULL_SHEET));
        List<String> ids = new ArrayList<>();
        for (List<String> row : sheet.subList(1, sheet.size())) ids.add(row.getFirst());
        if (!onlyIds.isEmpty()) ids.retainAll(onlyIds);

        Path raw = Path.of(outCsv.toString().replaceAll("\\.csv$", "") + ".jsonl");
        Map<String, String> config = config(model, effort, rubric);
        Map<String, Verdict> done = loadDone(raw, config);
        List<String> todo = ids.stream().filter(id -> !done.containsKey(id)).toList();
        System.out.printf(
                "판정 대상 %d개 (이미 판정 %d개, 새로 호출 %d개), 모델 %s, effort %s%n",
                ids.size(), ids.size() - todo.size(), todo.size(), model, effort);

        Map<String, Map<String, String>> items = new LinkedHashMap<>();
        for (Map<String, String> it : BlindJudging.readItems(exportDir, todo)) items.put(it.get("id"), it);

        AtomicLong in = new AtomicLong(),
                out = new AtomicLong(),
                cacheRead = new AtomicLong(),
                cacheWrite = new AtomicLong();
        int failed;
        AnthropicClient client = AnthropicOkHttpClient.fromEnv();
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (String id : todo) {
                futures.add(pool.submit(() -> {
                    Map<String, String> it = items.get(id);
                    Message m = client.messages()
                            .create(request(model, effort, system, userPrompt(it.get("response"), it.get("prompt"))));
                    in.addAndGet(m.usage().inputTokens());
                    out.addAndGet(m.usage().outputTokens());
                    cacheRead.addAndGet(m.usage().cacheReadInputTokens().orElse(0L));
                    cacheWrite.addAndGet(m.usage().cacheCreationInputTokens().orElse(0L));
                    Map<String, Object> rec = new LinkedHashMap<>();
                    rec.put("id", id);
                    rec.putAll(config);
                    // 서버 측 폴백이 다른 모델로 응답했을 수 있으므로 실제 응답 모델을 따로 남긴다
                    rec.put("model_used", m.model().asString());
                    rec.put(
                            "stop_reason",
                            m.stopReason().map(StopReason::asString).orElse(""));
                    String text = m.content().stream()
                            .flatMap(b -> b.text().stream())
                            .map(t -> t.text())
                            .findFirst()
                            .orElse(null);
                    boolean refused = m.stopReason()
                            .map(r -> r.asString().equals("refusal"))
                            .orElse(false);
                    if (!refused && text != null) {
                        parse(id, text); // 형식이 깨졌으면 여기서 실패해 기록하지 않음 → 다음 실행에서 다시 호출
                        rec.put("json", text);
                    }
                    synchronized (LlmJudge.class) {
                        Files.writeString(
                                raw,
                                JSON.writeValueAsString(rec) + "\n",
                                StandardCharsets.UTF_8,
                                StandardOpenOption.CREATE,
                                StandardOpenOption.APPEND);
                        if (rec.containsKey("json")) {
                            try {
                                done.put(id, parse(id, text));
                            } catch (IOException e) {
                                throw new IllegalStateException(e);
                            }
                        }
                        System.out.printf(
                                "  %s %s (%d/%d)%n",
                                id, refused ? "거절" : done.get(id).error() ? "X" : "O", done.size(), ids.size());
                    }
                    return null;
                }));
            }
            failed = 0;
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (Exception e) {
                    failed++;
                    System.out.println("  호출 실패: " + e.getCause());
                }
            }
            if (failed > 0) System.out.printf("실패 %d건 — 다시 실행하면 실패한 항목만 호출합니다.%n", failed);
        } finally {
            pool.shutdown();
            client.close();
        }

        Map<String, List<String>> meta = new LinkedHashMap<>();
        for (List<String> row : sheet.subList(1, sheet.size())) meta.put(row.get(0), row);
        List<List<String>> rows = new ArrayList<>();
        rows.add(sheet.getFirst());
        for (String id : ids) {
            Verdict v = done.get(id);
            if (v != null)
                rows.add(sheetRow(v, meta.get(id).get(1), meta.get(id).get(2)));
        }
        Csv.write(outCsv, rows);
        for (Metric m : Metric.values()) {
            List<List<String>> mrows = new ArrayList<>();
            mrows.add(sheet.getFirst());
            for (String id : ids) {
                Verdict v = done.get(id);
                if (v != null) mrows.add(metricRow(v, m, meta.get(id).get(1)));
            }
            Csv.write(Path.of(outCsv.toString().replaceAll("\\.csv$", "") + "_" + m.label + ".csv"), mrows);
        }

        long ambiguous = done.values().stream().filter(Verdict::ambiguous).count();
        long missing = done.values().stream().filter(Verdict::missingWeekTotal).count();
        long x = done.values().stream()
                .filter(v -> Boolean.TRUE.equals(v.error()))
                .count();
        System.out.printf("판정 %d개: X %d, 애매 %d, 이번 주 총지출 없음 %d → %s%n", rows.size() - 1, x, ambiguous, missing, outCsv);
        System.out.printf(
                "이번 실행 토큰: 입력 %d (캐시 읽기 %d, 캐시 쓰기 %d), 출력 %d%n",
                in.get(), cacheRead.get(), cacheWrite.get(), out.get());
        long untyped = done.values().stream()
                .filter(v -> Boolean.TRUE.equals(v.error()) && v.types().isEmpty())
                .count();
        if (untyped > 0) System.out.printf("오류 유형 없는 X 판정 %d개 — 사실 오류(%s)로 셉니다.%n", untyped, UNTYPED);
        // 판정이 빠진 항목이 있으면 결과를 쓴 뒤 실패로 끝내, 일부만 판정된 결과를 완료로 착각하지 않게 한다
        long missingVerdicts = ids.stream().filter(id -> !done.containsKey(id)).count();
        if (failed > 0 || missingVerdicts > 0)
            throw new IllegalStateException("판정하지 못한 항목 " + missingVerdicts + "개 (호출 실패 " + failed + "건) — 다시 실행하세요.");
    }

    static Set<String> ids(String v) {
        Set<String> s = new LinkedHashSet<>();
        for (String x : v.split(";")) if (!x.isBlank()) s.add(x.strip());
        return s;
    }
}
