package com.planus.backend.domain.aifeedback.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.planus.backend.domain.aifeedback.eval.RunLogParser.Kind;
import com.planus.backend.domain.aifeedback.eval.RunLogParser.ParsedRun;
import java.util.List;
import org.junit.jupiter.api.Test;

class RunLogParserTest {

    /** 실제 측정 로그와 같은 모양: Gradle이 테스트 출력을 4칸 들여 찍고, 끝에 Gradle 자체 출력이 붙는다. */
    static final List<String> LOG = List.of(
            "> Task :testLlm",
            "    === [운영 경로] 주간 피드백 (반복 2회) — 숫자 후검증 집계 ===",
            "    렌더 수               : 2",
            "",
            "    --- 검증에서 거절된 LLM 응답 1건 ---",
            "    [제17주차 r1 / 시도 1 | 최종: 2차 통과] 거절된 응답",
            "",
            "    --- 검증을 통과한 LLM 응답 2건 (누락 조사 대상) ---",
            "    [제17주차 r1 / 시도 2] 첫 줄 ⏎ 둘째 줄",
            "    [제18주차 r2 / 시도 1 | 새 규칙으로 통과] 18주차 응답",
            "",
            "    --- 컨텍스트별 프롬프트 2건 (응답 숫자 대조용) ---",
            "    [제17주차]",
            "    아래 분석 재료로 이번 주 소비 피드백을 작성해줘.",
            "    ",
            "    [예산·저축 — 1순위]",
            "    - 가용예산: 1,000,000원",
            "    [제18주차]",
            "    18주차 재료",
            "",
            "    === [운영 경로] 월말 결산 (반복 1회) — 숫자 후검증 집계 ===",
            "    --- 검증을 통과한 LLM 응답 1건 (누락 조사 대상) ---",
            "    [2026-02 월말 r1 / 시도 1] 2월 결산 응답",
            "    --- 컨텍스트별 프롬프트 1건 (응답 숫자 대조용) ---",
            "    [2026-02 월말]",
            "    2026년 2월 결산 피드백을 작성해줘.",
            "",
            "",
            "BUILD SUCCESSFUL in 8m 13s");

    @Test
    void 통과한_응답만_꺼내고_거절된_응답은_뺀다() {
        ParsedRun run = RunLogParser.parse(LOG);

        assertThat(run.samples())
                .extracting(s -> s.context() + "/" + s.rep() + "/" + s.kind())
                .containsExactly("제17주차/1/WEEKLY", "제18주차/2/WEEKLY", "2026-02 월말/1/MONTH_END");
    }

    @Test
    void 응답의_줄바꿈_표시를_복원한다() {
        assertThat(RunLogParser.parse(LOG).samples().getFirst().text()).isEqualTo("첫 줄\n둘째 줄");
    }

    @Test
    void 프롬프트_안의_대괄호_줄은_머리줄로_보지_않는다() {
        ParsedRun run = RunLogParser.parse(LOG);

        assertThat(run.prompts()).containsOnlyKeys("제17주차", "제18주차", "2026-02 월말");
        assertThat(run.prompts().get("제17주차"))
                .startsWith("아래 분석 재료로")
                .contains("[예산·저축 — 1순위]")
                .endsWith("- 가용예산: 1,000,000원");
        assertThat(run.prompts().get("제18주차")).isEqualTo("18주차 재료");
    }

    @Test
    void Gradle_출력이_나오면_마지막_프롬프트를_닫는다() {
        assertThat(RunLogParser.parse(LOG).prompts().get("2026-02 월말")).isEqualTo("2026년 2월 결산 피드백을 작성해줘.");
    }

    @Test
    void 반복_표기가_없으면_rep는_0() {
        ParsedRun run = RunLogParser.parse(List.of(
                "    === [운영 경로] 주간 피드백 (반복 1회) — 숫자 후검증 집계 ===",
                "    --- 검증을 통과한 LLM 응답 1건 (누락 조사 대상) ---",
                "    [제22주차 / 시도 1] 응답"));

        assertThat(run.samples()).singleElement().satisfies(s -> {
            assertThat(s.rep()).isZero();
            assertThat(s.kind()).isEqualTo(Kind.WEEKLY);
        });
    }
}
