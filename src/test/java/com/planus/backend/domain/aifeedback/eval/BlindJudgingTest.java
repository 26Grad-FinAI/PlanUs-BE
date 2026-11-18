package com.planus.backend.domain.aifeedback.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.planus.backend.domain.aifeedback.eval.BlindJudging.Csv;
import com.planus.backend.domain.aifeedback.eval.BlindJudging.KeyRow;
import com.planus.backend.domain.aifeedback.eval.RunLogParser.Kind;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BlindJudgingTest {

    @TempDir
    Path tmp;

    @Test
    void 내보낸_시트에는_버전이_없고_키로만_되돌릴_수_있다() throws Exception {
        Path out = exportTwoVersions();

        String sheet = Files.readString(out.resolve(BlindJudging.FULL_SHEET), StandardCharsets.UTF_8);
        assertThat(sheet)
                .startsWith("﻿")
                .doesNotContain("vA")
                .doesNotContain("vB")
                .doesNotContain("제17주차");

        Map<String, KeyRow> key = BlindJudging.readKey(out.resolve("_key/key.csv"));
        assertThat(key).hasSize(6);
        assertThat(key.values()).extracting(KeyRow::version).containsOnly("vA", "vB");
        // 항목마다 사람이 읽는 Markdown과 도구가 읽는 JSON
        assertThat(Files.list(out.resolve("items")).filter(f -> f.toString().endsWith(".md")))
                .hasSize(6);
        assertThat(Files.list(out.resolve("items")).filter(f -> f.toString().endsWith(".json")))
                .hasSize(6);
        assertThat(Files.readString(
                        out.resolve("items").resolve(key.keySet().iterator().next() + ".json")))
                .doesNotContain("vA")
                .doesNotContain("vB");
    }

    @Test
    void 항목_파일에는_응답과_그_응답을_만든_프롬프트가_있다() throws Exception {
        Path out = exportTwoVersions();
        Map<String, KeyRow> key = BlindJudging.readKey(out.resolve("_key/key.csv"));
        KeyRow anyB = key.values().stream()
                .filter(k -> k.version().equals("vB"))
                .findFirst()
                .orElseThrow();

        String item = Files.readString(out.resolve("items/" + anyB.id() + ".md"), StandardCharsets.UTF_8);

        assertThat(item).contains("베타 응답").contains("베타 재료").doesNotContain("알파 재료");
    }

    @Test
    void 같은_시드면_같은_순서로_섞인다() throws Exception {
        Path a = exportTwoVersions(), b = tmp.resolve("again");
        BlindJudging.export(logs(), b, 140);

        assertThat(Files.readString(b.resolve("_key/key.csv"))).isEqualTo(Files.readString(a.resolve("_key/key.csv")));
    }

    @Test
    void 이미_있는_폴더에는_내보내지_않는다() throws Exception {
        Path out = exportTwoVersions();

        assertThatThrownBy(() -> BlindJudging.export(logs(), out, 140)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 판정을_버전별로_집계하고_판정자_일치도를_낸다() throws Exception {
        Path out = exportTwoVersions();
        Map<String, KeyRow> key = BlindJudging.readKey(out.resolve("_key/key.csv"));

        // 판정자 1: vA 전부 틀림, vB 전부 맞음 / 판정자 2: 같되 vB 하나를 틀림으로
        String disagreeId = key.values().stream()
                .filter(k -> k.version().equals("vB"))
                .findFirst()
                .orElseThrow()
                .id();
        Path j1 = fillSheet(out, key, "j1.csv", k -> k.version().equals("vA") ? "X" : "O");
        Path j2 = fillSheet(out, key, "j2.csv", k -> k.version().equals("vA") || k.id().equals(disagreeId) ? "X" : "O");

        String report = BlindJudging.score(out.resolve("_key/key.csv"), List.of(j1, j2), null);

        assertThat(report)
                .contains("| vA | 주간 | 2/2 | 100.0% |")
                .contains("| vA | 월말 | 1/1 | 100.0% |")
                .contains("| vB | 전체 | 0/3 | 0.0% |")
                .contains("| vB | 전체 | 1/3 | 33.3% |")
                .contains("vA vs vB: p =")
                .contains("함께 판정 6개, 일치 5개")
                .contains("불일치 항목 (합의 대상): " + disagreeId)
                .contains("숫자 대상: {vA=3}");
    }

    @Test
    void 판정_값_오타는_거부한다() {
        assertThat(BlindJudging.parseLabel(" x ", "J001")).isTrue();
        assertThat(BlindJudging.parseLabel("맞음", "J001")).isFalse();
        assertThat(BlindJudging.parseLabel("", "J001")).isNull();
        assertThatThrownBy(() -> BlindJudging.parseLabel("XX", "J001")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 주간만_내보낼_수_있다() throws Exception {
        Path out = tmp.resolve("weekly");
        int n = BlindJudging.export(logs(), out, 140, EnumSet.of(Kind.WEEKLY), 0);

        assertThat(n).isEqualTo(4);
        assertThat(BlindJudging.readKey(out.resolve("_key/key.csv")).values())
                .extracting(KeyRow::kind)
                .containsOnly("주간");
        assertThat(out.resolve(BlindJudging.SUBSET_SHEET)).doesNotExist();
    }

    @Test
    void 판정자2_시트는_전체_시트의_일부를_같은_id로_담는다() throws Exception {
        Path out = tmp.resolve("subset");
        BlindJudging.export(logs(), out, 140, EnumSet.allOf(Kind.class), 4);

        List<List<String>> full = Csv.read(out.resolve(BlindJudging.FULL_SHEET));
        List<List<String>> part = Csv.read(out.resolve(BlindJudging.SUBSET_SHEET));
        assertThat(part).hasSize(1 + 4);
        assertThat(part.getFirst()).isEqualTo(full.getFirst());
        assertThat(full).containsAll(part);

        Map<String, KeyRow> key = BlindJudging.readKey(out.resolve("_key/key.csv"));
        assertThat(part.subList(1, part.size()))
                .extracting(r -> key.get(r.getFirst()).version())
                .containsExactlyInAnyOrder("vA", "vA", "vB", "vB");
    }

    @Test
    void 층화_추출은_그룹_비율대로_고르고_나머지는_큰_쪽부터_준다() {
        List<String> groups = new ArrayList<>();
        for (int i = 0; i < 69; i++) groups.add("v3.0");
        for (int i = 0; i < 69; i++) groups.add("v3.9");
        for (int i = 0; i < 69; i++) groups.add("v4.1");

        Set<Integer> picked = BlindJudging.stratifiedSample(groups, 70, new Random(1));

        assertThat(picked).hasSize(70);
        Map<String, Long> perGroup = picked.stream()
                .collect(java.util.stream.Collectors.groupingBy(groups::get, java.util.stream.Collectors.counting()));
        assertThat(perGroup.values()).allSatisfy(c -> assertThat(c).isBetween(23L, 24L));
        assertThat(BlindJudging.stratifiedSample(groups, 70, new Random(1))).isEqualTo(picked);
    }

    @Test
    void 판정_화면은_시트마다_만들고_버전_정보는_넣지_않는다() throws Exception {
        Path out = tmp.resolve("page");
        BlindJudging.export(logs(), out, 140, EnumSet.allOf(Kind.class), 2);

        String full = Files.readString(out.resolve("판정자1.html"), StandardCharsets.UTF_8);
        String part = Files.readString(out.resolve("판정자2.html"), StandardCharsets.UTF_8);

        assertThat(full).contains("베타 응답 1").contains("베타 재료");
        assertThat(pageItems(full).toString()).doesNotContain("vA").doesNotContain("vB");
        assertThat(full).doesNotContain("__DATA__").doesNotContain("__TITLE__");
        assertThat(pageItems(full)).hasSize(6);
        assertThat(pageItems(part)).hasSize(2);
    }

    @Test
    void 판정_화면에_넣는_JSON은_script_태그를_닫지_않는다() throws Exception {
        Path page = tmp.resolve("p.html");

        BlindJudging.writePage(
                page, "t", List.of(Map.of("id", "J001", "kind", "주간", "response", "a</script>b", "prompt", "p")));

        String html = Files.readString(page, StandardCharsets.UTF_8);
        assertThat(html).doesNotContain("a</script>b");
        assertThat(pageItems(html).get(0).get("response").asText()).isEqualTo("a</script>b");
    }

    @Test
    void 일부_항목만_다시_읽어_판정_화면을_만들_수_있다() throws Exception {
        Path out = exportTwoVersions();
        Map<String, KeyRow> key = BlindJudging.readKey(out.resolve("_key/key.csv"));
        KeyRow monthEnd = key.values().stream()
                .filter(k -> k.kind().equals("월말") && k.version().equals("vB"))
                .findFirst()
                .orElseThrow();

        List<Map<String, String>> items = BlindJudging.readItems(out, List.of(monthEnd.id()));

        assertThat(items).singleElement().satisfies(it -> {
            assertThat(it).containsEntry("id", monthEnd.id()).containsEntry("kind", "월말");
            assertThat(it.get("response")).isEqualTo("베타 결산");
            assertThat(it.get("prompt")).isEqualTo("베타 결산 재료");
        });
    }

    @Test
    void CSV는_쉼표_따옴표_줄바꿈이_든_칸을_보존한다() throws Exception {
        Path p = tmp.resolve("t.csv");
        List<List<String>> rows = List.of(List.of("a", "b,c", "say \"hi\"", "line1\nline2"), List.of("", "x", "", ""));

        Csv.write(p, rows);

        assertThat(Csv.read(p)).isEqualTo(rows);
    }

    // ── 픽스처 ──

    private static com.fasterxml.jackson.databind.JsonNode pageItems(String html) throws Exception {
        String start = "<script type=\"application/json\" id=\"data\">";
        int from = html.indexOf(start) + start.length();
        String json = html.substring(from, html.indexOf("</script>", from));
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json).get("items");
    }

    private Path exportTwoVersions() throws Exception {
        Path out = tmp.resolve("out");
        BlindJudging.export(logs(), out, 140);
        return out;
    }

    private Map<String, Path> logs() throws Exception {
        Map<String, Path> logs = new LinkedHashMap<>();
        logs.put("vA", writeLog("a.log", "vA"));
        logs.put("vB", writeLog("b.log", "vB"));
        return logs;
    }

    private Path writeLog(String name, String version) throws Exception {
        String v = version.equals("vA") ? "알파" : "베타"; // 응답·프롬프트 본문에는 버전 라벨이 없어야 블라인드 검사가 의미 있음
        Path p = tmp.resolve(name);
        if (Files.exists(p)) return p;
        Files.write(
                p,
                List.of(
                        "    === [운영 경로] 주간 피드백 (반복 2회) — 숫자 후검증 집계 ===",
                        "    --- 검증을 통과한 LLM 응답 2건 (누락 조사 대상) ---",
                        "    [제17주차 r1 / 시도 1] " + v + " 응답 1",
                        "    [제17주차 r2 / 시도 1] " + v + " 응답 2",
                        "    --- 컨텍스트별 프롬프트 1건 (응답 숫자 대조용) ---",
                        "    [제17주차]",
                        "    " + v + " 재료",
                        "    === [운영 경로] 월말 결산 (반복 1회) — 숫자 후검증 집계 ===",
                        "    --- 검증을 통과한 LLM 응답 1건 (누락 조사 대상) ---",
                        "    [2026-02 월말 r1 / 시도 1] " + v + " 결산",
                        "    --- 컨텍스트별 프롬프트 1건 (응답 숫자 대조용) ---",
                        "    [2026-02 월말]",
                        "    " + v + " 결산 재료",
                        "BUILD SUCCESSFUL"),
                StandardCharsets.UTF_8);
        return p;
    }

    private static Path fillSheet(
            Path out, Map<String, KeyRow> key, String name, java.util.function.Function<KeyRow, String> label)
            throws Exception {
        List<List<String>> rows = Csv.read(out.resolve(BlindJudging.FULL_SHEET));
        List<List<String>> filled = new ArrayList<>();
        filled.add(rows.getFirst());
        for (List<String> row : rows.subList(1, rows.size())) {
            KeyRow k = key.get(row.getFirst());
            String l = label.apply(k);
            filled.add(List.of(row.get(0), row.get(1), row.get(2), l, l.equals("X") ? "숫자 대상" : "", ""));
        }
        Path p = out.resolve(name);
        Csv.write(p, filled);
        return p;
    }

    @Test
    void 부분_시트_수가_너무_크면_폴더를_만들지_않고_다시_실행할_수_있다() throws Exception {
        Path out = tmp.resolve("big");

        assertThatThrownBy(() -> BlindJudging.export(logs(), out, 140, EnumSet.allOf(Kind.class), 99))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(out).doesNotExist();
        assertThat(BlindJudging.export(logs(), out, 140, EnumSet.allOf(Kind.class), 2))
                .isEqualTo(6);
    }

    @Test
    void 판정_화면_제목은_마크업이_아닌_텍스트로_들어간다() throws Exception {
        Path page = tmp.resolve("t.html");

        BlindJudging.writePage(
                page, "<img src=x>", List.of(Map.of("id", "J001", "kind", "주간", "response", "r", "prompt", "p")));

        String html = Files.readString(page, StandardCharsets.UTF_8);
        // HTML 문맥(<title>, <h1>)에는 인코딩되어 들어간다. 데이터 JSON 안의 원문은 JSON.parse로만 읽고 HTML로 넣지 않는다
        assertThat(html)
                .contains("<title>AI 피드백 판정 — &lt;img src=x&gt;</title>")
                .contains("<h1>AI 피드백 판정 · &lt;img src=x&gt;</h1>");
        String outsideData = html.substring(0, html.indexOf("<script type=\"application/json\""));
        assertThat(outsideData).doesNotContain("<img src=x>");
    }

    @Test
    void 응답에_코드_펜스가_있어도_항목을_원본대로_읽는다() throws Exception {
        Path dir = tmp.resolve("fence");
        Files.createDirectories(dir.resolve("items"));
        // JSON이 없는 이전 형식: Markdown만으로 나눠야 하는 경우
        Files.writeString(
                dir.resolve("items/J001.md"),
                "# J001 (주간)\n\n## 응답\n\n앞\n```\n코드\n```\n뒤\n\n## 분석 재료 (이 응답을 만든 프롬프트)\n\n```\n재료\n```\n",
                StandardCharsets.UTF_8);

        Map<String, String> it = BlindJudging.readItems(dir, List.of("J001")).getFirst();

        assertThat(it.get("response")).isEqualTo("앞\n```\n코드\n```\n뒤");
        assertThat(it.get("prompt")).isEqualTo("재료");
    }

    @Test
    void 다른_내보내기의_시트는_같은_id여도_집계하지_않는다() throws Exception {
        Path a = exportTwoVersions(), b = tmp.resolve("other-seed");
        BlindJudging.export(logs(), b, 7, EnumSet.allOf(Kind.class), 0);
        Map<String, KeyRow> keyA = BlindJudging.readKey(a.resolve("_key/key.csv"));
        Map<String, KeyRow> keyB = BlindJudging.readKey(b.resolve("_key/key.csv"));
        String moved = keyA.keySet().stream()
                .filter(id -> !keyA.get(id).responseSha().equals(keyB.get(id).responseSha()))
                .findFirst()
                .orElseThrow();
        Path sheetA = fillSheet(a, keyA, "a.csv", k -> "O");

        assertThatThrownBy(() -> BlindJudging.score(b.resolve("_key/key.csv"), List.of(sheetA), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(moved);
        Path sheetB = fillSheet(b, keyB, "b.csv", k -> "O");
        assertThatThrownBy(() -> BlindJudging.agreement(
                        BlindJudging.readSheet(sheetA, null), BlindJudging.readSheet(sheetB, null)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 버전_비교는_주차_단위_순열_검정으로_보고한다() throws Exception {
        Path out = exportTwoVersions();
        Map<String, KeyRow> key = BlindJudging.readKey(out.resolve("_key/key.csv"));
        Path sheet = fillSheet(out, key, "s.csv", k -> k.version().equals("vA") ? "X" : "O");

        String report = BlindJudging.score(out.resolve("_key/key.csv"), List.of(sheet), null);

        assertThat(report)
                .contains("순열 검정")
                .contains("vA vs vB: p =")
                .contains("(주차 2개)")
                .doesNotContain("Fisher");
    }
}
