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
        assertThat(schema.toString())
                .contains(LlmJudge.ERROR_TYPES.toString().replace("[", "").replace("]", ""));
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
}
