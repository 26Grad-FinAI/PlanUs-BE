package com.planus.backend.domain.aifeedback.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.planus.backend.domain.aifeedback.eval.LlmJudge.Verdict;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LlmJudgeTest {

    @Test
    void 구조화_출력을_판정으로_바꾸고_중복_유형은_한_번만_남긴다() throws Exception {
        String json =
                """
                {"verdict":"X","errors":[
                  {"type":"대상기준","quote":"이번 주는 예산의 30%","evidence":"30%는 이번 달 누적"},
                  {"type":"대상기준","quote":"평소보다","evidence":"전주 비교"},
                  {"type":"기간","quote":"월 9만 원","evidence":"남은 기간 금액"}],
                 "ambiguous":true,"ambiguous_reason":"표현이 약함","missing_week_total":true}
                """;

        Verdict v = LlmJudge.parse("J001", json);

        assertThat(v.error()).isTrue();
        assertThat(v.types()).containsExactly("대상기준", "기간");
        assertThat(v.evidence()).hasSize(3).first().asString().contains("이번 주는 예산의 30%");
        assertThat(v.ambiguous()).isTrue();
        assertThat(v.missingWeekTotal()).isTrue();
    }

    @Test
    void 시트_한_줄은_사람_판정_시트와_같은_열이고_태그를_메모_앞에_둔다() {
        Verdict v = new Verdict("J007", true, List.of("기간"), List.of("기간: \"월\" — 남은 기간"), true, "경계", true);

        List<String> row = LlmJudge.sheetRow(v, "주간", "응답");

        assertThat(row).hasSize(6);
        assertThat(row.subList(0, 5)).containsExactly("J007", "주간", "응답", "X", "기간");
        assertThat(row.get(5)).startsWith("[애매] 경계 [총지출없음]").contains("남은 기간");
        assertThat(BlindJudging.parseLabel(row.get(3), "J007")).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void 스키마는_오류_유형을_기준서_코드로_제한하고_모든_필드가_필수다() {
        Map<String, Object> schema = LlmJudge.schema();

        assertThat(schema).containsEntry("additionalProperties", false);
        assertThat((List<Object>) schema.get("required"))
                .containsExactly("verdict", "errors", "ambiguous", "ambiguous_reason", "missing_week_total");
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        Map<String, Object> errors = (Map<String, Object>) props.get("errors");
        Map<String, Object> item = (Map<String, Object>) errors.get("items");
        Map<String, Object> type = (Map<String, Object>) ((Map<String, Object>) item.get("properties")).get("type");
        assertThat((List<Object>) type.get("enum")).containsExactlyElementsOf(LlmJudge.ERROR_TYPES);
    }

    @Test
    void 시스템_프롬프트에_기준서를_그대로_넣고_요청은_모델과_형식을_지정한다() {
        String system = LlmJudge.systemPrompt("기준서 본문");

        assertThat(system).contains("<판정_기준서>\n기준서 본문\n</판정_기준서>").contains("감정:");
        var params = LlmJudge.request("claude-opus-5-5", "medium", system, LlmJudge.userPrompt("응답", "재료"));
        assertThat(params.model().asString()).isEqualTo("claude-opus-5-5");
        assertThat(params.outputConfig()).isPresent();
        assertThat(params.messages().getFirst().content().toString())
                .contains("재료")
                .contains("응답");
    }

    @Test
    void 지표_시트는_사실오류와_조언품질을_나눠_X로_표시한다() {
        Verdict fact = new Verdict("J001", true, List.of("대상기준"), List.of(), false, "", false);
        Verdict advice = new Verdict("J002", true, List.of("원인", "톤"), List.of(), false, "", true);
        Verdict ok = new Verdict("J003", false, List.of(), List.of(), false, "", false);

        assertThat(LlmJudge.metricRow(fact, LlmJudge.Metric.FACT, "주간").get(3)).isEqualTo("X");
        assertThat(LlmJudge.metricRow(fact, LlmJudge.Metric.ADVICE, "주간").get(3))
                .isEqualTo("O");
        assertThat(LlmJudge.metricRow(advice, LlmJudge.Metric.FACT, "주간").get(3))
                .isEqualTo("O");
        assertThat(LlmJudge.metricRow(advice, LlmJudge.Metric.ADVICE, "주간").get(3))
                .isEqualTo("X");
        assertThat(LlmJudge.metricRow(advice, LlmJudge.Metric.MISSING_WEEK_TOTAL, "주간")
                        .get(3))
                .isEqualTo("X");
        assertThat(LlmJudge.metricRow(ok, LlmJudge.Metric.FACT, "주간").get(3)).isEqualTo("O");
    }

    @Test
    void 지표_시트는_그_지표의_유형만_쓰고_유형_없는_X는_사실_오류로_센다() {
        Verdict both = new Verdict("J001", true, List.of("대상기준", "원인"), List.of(), false, "", false);
        Verdict untyped = new Verdict("J002", true, List.of(), List.of(), false, "", false);

        assertThat(LlmJudge.metricRow(both, LlmJudge.Metric.FACT, "주간").get(4)).isEqualTo("대상기준");
        assertThat(LlmJudge.metricRow(both, LlmJudge.Metric.ADVICE, "주간").get(4))
                .isEqualTo("원인");
        assertThat(LlmJudge.metricRow(untyped, LlmJudge.Metric.FACT, "주간")).containsSubsequence("X", LlmJudge.UNTYPED);
        assertThat(LlmJudge.metricRow(untyped, LlmJudge.Metric.ADVICE, "주간").get(3))
                .isEqualTo("O");
    }

    @Test
    void 총지출_누락은_주간_응답만_집계한다() {
        Verdict v = new Verdict("J003", false, List.of(), List.of(), false, "", true);

        assertThat(LlmJudge.metricRow(v, LlmJudge.Metric.MISSING_WEEK_TOTAL, "주간")
                        .get(3))
                .isEqualTo("X");
        assertThat(LlmJudge.metricRow(v, LlmJudge.Metric.MISSING_WEEK_TOTAL, "월말")
                        .get(3))
                .isEmpty();
    }

    @Test
    void 같은_설정의_기록만_재사용하고_잘린_줄은_건너뛴다(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        Map<String, String> config = LlmJudge.config("m", "medium", "기준서 A");
        Map<String, String> other = LlmJudge.config("m", "medium", "기준서 B");
        String ok =
                "{\"verdict\":\"O\",\"errors\":[],\"ambiguous\":false,\"ambiguous_reason\":\"\",\"missing_week_total\":false}";
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        java.util.List<String> lines = new java.util.ArrayList<>();
        java.util.Map<String, Object> a = new java.util.LinkedHashMap<>(config);
        a.put("id", "J001");
        a.put("json", ok);
        java.util.Map<String, Object> b = new java.util.LinkedHashMap<>(other);
        b.put("id", "J002");
        b.put("json", ok);
        lines.add(json.writeValueAsString(a));
        lines.add(json.writeValueAsString(b));
        lines.add("{\"id\":\"J003\",\"json\":\"{\\\"verd"); // 중단돼 잘린 마지막 줄
        java.nio.file.Path raw = tmp.resolve("r.jsonl");
        java.nio.file.Files.write(raw, lines, java.nio.charset.StandardCharsets.UTF_8);

        assertThat(LlmJudge.loadDone(raw, config)).containsOnlyKeys("J001");
    }
}
