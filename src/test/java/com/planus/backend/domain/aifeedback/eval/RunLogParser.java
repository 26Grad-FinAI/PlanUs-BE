package com.planus.backend.domain.aifeedback.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 운영 경로 LLM 측정 로그({@code ProductionPathValidationTest} + {@code LlmRenderMeter} 출력)에서 사용자에게 나간 응답과 그 응답을 만든 프롬프트를
 * 꺼낸다 (#140, 테스트 전용).
 *
 * <p>Gradle은 테스트 표준 출력을 4칸 들여 찍으므로, 들여쓴 줄만 테스트 출력으로 본다. 들여쓰지 않은 줄(Gradle 자체 출력)이 나오면 프롬프트 구간이
 * 끝난 것으로 본다.
 *
 * <p>프롬프트 안에도 "[예산·저축 — 1순위]"처럼 대괄호 줄이 있으므로, 프롬프트 머리 줄은 컨텍스트 이름 형식("[제17주차]", "[2026-02 월말]")일 때만
 * 인정한다. 모든 시도가 거절돼 템플릿으로 나간 컨텍스트의 프롬프트도 따로 저장된다 (판정할 응답은 없음).
 */
public final class RunLogParser {

    /** 피드백 종류. */
    public enum Kind {
        WEEKLY("주간"),
        MONTH_END("월말");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * 사용자에게 나간 LLM 응답 1개.
     *
     * @param context 컨텍스트 이름 (예: "제17주차", "2026-02 월말")
     * @param rep     반복 번호 (1부터, 반복 표기가 없으면 0)
     * @param kind    주간/월말
     * @param text    응답 원문 (줄바꿈 복원)
     */
    public record Sample(String context, int rep, Kind kind, String text) {}

    /** 로그 1개에서 꺼낸 응답과 컨텍스트별 프롬프트. */
    public record ParsedRun(List<Sample> samples, Map<String, String> prompts) {}

    private static final String INDENT = "    ";
    private static final Pattern ACCEPTED_LINE =
            Pattern.compile("^\\[(.+?)(?: r(\\d+))? / 시도 \\d+(?: \\| [^\\]]*)?\\] (.*)$");
    /** 컨텍스트 머리 줄 형식: "[제17주차]", "[2026-02 월말]". 프롬프트 안의 "[예산·저축 — 1순위]" 같은 줄과 구분된다. */
    private static final Pattern HEADER_LINE = Pattern.compile("^\\[(제\\d+주차|\\d{4}-\\d{2} 월말)\\]$");

    private enum Section {
        NONE,
        ACCEPTED,
        PROMPTS
    }

    private RunLogParser() {}

    public static ParsedRun parse(Path log) throws IOException {
        return parse(Files.readAllLines(log, StandardCharsets.UTF_8));
    }

    public static ParsedRun parse(List<String> lines) {
        List<Sample> samples = new ArrayList<>();
        Map<String, String> prompts = new LinkedHashMap<>();

        Section section = Section.NONE;
        Kind kind = null;
        String promptLabel = null;
        StringBuilder prompt = new StringBuilder();

        for (String raw : lines) {
            boolean indented = raw.startsWith(INDENT);
            String line = indented ? raw.substring(INDENT.length()) : raw;

            if (!indented && !raw.isBlank()) { // Gradle 자체 출력 → 테스트 출력 구간 끝
                promptLabel = flush(prompts, promptLabel, prompt);
                section = Section.NONE;
                continue;
            }
            if (line.startsWith("=== ")) {
                promptLabel = flush(prompts, promptLabel, prompt);
                section = Section.NONE;
                if (line.contains("월말 결산")) kind = Kind.MONTH_END;
                else if (line.contains("주간 피드백") && line.contains("후검증")) kind = Kind.WEEKLY;
                continue;
            }
            if (line.startsWith("--- ")) {
                promptLabel = flush(prompts, promptLabel, prompt);
                if (line.startsWith("--- 검증을 통과한 LLM 응답")) section = Section.ACCEPTED;
                else if (line.startsWith("--- 컨텍스트별 프롬프트")) section = Section.PROMPTS;
                else section = Section.NONE;
                continue;
            }

            switch (section) {
                case ACCEPTED -> {
                    Matcher m = ACCEPTED_LINE.matcher(line);
                    if (m.matches() && kind != null) {
                        String context = m.group(1);
                        int rep = m.group(2) != null ? Integer.parseInt(m.group(2)) : 0;
                        samples.add(new Sample(context, rep, kind, m.group(3).replace(" ⏎ ", "\n")));
                    }
                }
                case PROMPTS -> {
                    Matcher h = HEADER_LINE.matcher(line);
                    // 모든 시도가 거절돼 통과 응답이 없는 컨텍스트도 머리 줄로 인정해, 그 프롬프트가 앞 컨텍스트에 붙지 않게 한다
                    if (h.matches()) {
                        flush(prompts, promptLabel, prompt);
                        promptLabel = h.group(1);
                    } else if (promptLabel != null) {
                        prompt.append(line).append('\n');
                    }
                }
                case NONE -> {}
            }
        }
        flush(prompts, promptLabel, prompt);
        return new ParsedRun(List.copyOf(samples), prompts);
    }

    /** 모아 둔 프롬프트를 저장하고 버퍼를 비운다. 항상 null을 돌려줘 호출부가 현재 라벨을 지우게 한다. */
    private static String flush(Map<String, String> prompts, String label, StringBuilder buf) {
        if (label != null) prompts.putIfAbsent(label, buf.toString().strip());
        buf.setLength(0);
        return null;
    }
}
