package com.planus.backend.domain.aifeedback.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planus.backend.domain.aifeedback.eval.JudgingStats.Interval;
import com.planus.backend.domain.aifeedback.eval.RunLogParser.Kind;
import com.planus.backend.domain.aifeedback.eval.RunLogParser.ParsedRun;
import com.planus.backend.domain.aifeedback.eval.RunLogParser.Sample;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 블라인드 판정 도구 (#140, 테스트 전용).
 *
 * <p>여러 코드 버전의 측정 로그에서 사용자에게 나간 응답을 꺼내 섞고, 버전을 숨긴 ID를 붙여 판정 시트로 내보낸다(export). 판정이 끝나면 숨긴 키로 다시 버전별로
 * 묶어 오류율·신뢰구간·버전 간 검정·판정자 일치도를 계산한다(score).
 *
 * <p>판정자는 자기 시트({@code 판정자1.csv}·{@code 판정자2.csv})와 {@code items/}만 본다. {@code _key/key.csv}는 집계 전까지 열지 않는다.
 *
 * <pre>
 * ./gradlew judge -Pcmd=export -Pout=판정폴더 -Plogs="v3.0=a.log;v3.9=b.log;v4.1=c.log" [-Pkinds=주간] [-Psubset=70] [-Pseed=140]
 * ./gradlew judge -Pcmd=page -Pdir=판정폴더 -Pids="J026;J031" -Pout=추가.html [-Ptitle=추가 판정]
 * ./gradlew judge -Pcmd=score -Pkey=판정폴더/_key/key.csv -Psheets="판정자1.csv;판정자2.csv" [-Pfinal=합의.csv] [-Preport=report.md]
 * </pre>
 */
public final class BlindJudging {

    static final String SHEET_HEADER_ID = "id";
    static final String COL_LABEL = "판정(O=맞음, X=틀림)";
    static final String COL_TYPE = "오류 유형";
    private static final int BOOTSTRAP_ITERATIONS = 5000;
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 키 파일 한 줄. */
    public record KeyRow(String id, String version, String kind, String context, int rep) {}

    /** 판정 시트 한 줄. error가 null이면 아직 판정하지 않은 항목. */
    public record Judgement(String id, Boolean error, String types, String memo) {}

    private BlindJudging() {}

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = parseArgs(args);
        switch (opt.getOrDefault("cmd", "")) {
            case "export" -> {
                Map<String, Path> logs = new LinkedHashMap<>();
                for (String pair : split(require(opt, "logs"))) {
                    int eq = pair.indexOf('=');
                    if (eq <= 0) throw new IllegalArgumentException("logs 형식: 버전=경로;버전=경로 — " + pair);
                    logs.put(
                            pair.substring(0, eq).trim(),
                            Path.of(pair.substring(eq + 1).trim()));
                }
                long seed = Long.parseLong(opt.getOrDefault("seed", "140"));
                Set<Kind> kinds = EnumSet.noneOf(Kind.class);
                for (String k : split(opt.getOrDefault("kinds", "주간;월말"))) {
                    kinds.add(Arrays.stream(Kind.values())
                            .filter(x -> x.label().equals(k))
                            .findFirst()
                            .orElseThrow(() -> new IllegalArgumentException("kinds는 주간·월말: " + k)));
                }
                int subset = Integer.parseInt(opt.getOrDefault("subset", "0"));
                int n = export(logs, Path.of(require(opt, "out")), seed, kinds, subset);
                System.out.printf("판정 항목 %d개를 내보냈습니다: %s%n", n, opt.get("out"));
            }
            case "score" -> {
                List<Path> sheets =
                        split(require(opt, "sheets")).stream().map(Path::of).toList();
                Path fin = opt.containsKey("final") ? Path.of(opt.get("final")) : null;
                String report = score(Path.of(require(opt, "key")), sheets, fin);
                System.out.println(report);
                if (opt.containsKey("report"))
                    Files.writeString(Path.of(opt.get("report")), report, StandardCharsets.UTF_8);
            }
            case "page" -> {
                List<String> ids = split(require(opt, "ids"));
                Path out = Path.of(require(opt, "out"));
                writePage(out, opt.getOrDefault("title", "추가 판정"), readItems(Path.of(require(opt, "dir")), ids));
                System.out.printf("판정 화면 %d개 항목: %s%n", ids.size(), out);
            }
            case "llm" ->
                LlmJudge.run(
                        Path.of(require(opt, "dir")),
                        Path.of(require(opt, "out")),
                        opt.containsKey("ids") ? split(opt.get("ids")) : List.of(),
                        opt.getOrDefault("judgeModel", LlmJudge.DEFAULT_MODEL),
                        opt.getOrDefault("effort", "medium"));
            case "agree" -> {
                List<Path> sheets =
                        split(require(opt, "sheets")).stream().map(Path::of).toList();
                if (sheets.size() != 2) throw new IllegalArgumentException("agree는 시트 2개: 기준;비교");
                String report = agreement(readSheet(sheets.get(0), null), readSheet(sheets.get(1), null));
                System.out.println(report);
                if (opt.containsKey("report"))
                    Files.writeString(Path.of(opt.get("report")), report, StandardCharsets.UTF_8);
            }
            default -> throw new IllegalArgumentException("cmd는 export, score, page, llm, agree 중 하나");
        }
    }

    // ── export ──

    /** 전체 항목을 판정하는 판정자의 시트. */
    static final String FULL_SHEET = "판정자1.csv";
    /** 일부 항목만 판정하는 판정자의 시트 (판정자 일치도 계산용). */
    static final String SUBSET_SHEET = "판정자2.csv";

    /** 주간·월말 모두, 부분 시트 없이 내보낸다. */
    public static int export(Map<String, Path> logsByVersion, Path outDir, long seed) throws IOException {
        return export(logsByVersion, outDir, seed, EnumSet.allOf(Kind.class), 0);
    }

    /**
     * 판정 시트·항목 파일·키를 만든다. 이미 판정한 시트를 덮어쓰지 않도록 출력 폴더가 있으면 거부한다.
     *
     * <p>{@value #FULL_SHEET}에는 전체 항목, {@value #SUBSET_SHEET}에는 그중 {@code subsetSize}개를 버전별 비율대로 고른 항목이 같은 id로
     * 들어간다. 두 시트가 겹치는 항목으로 판정자 일치도를 계산한다.
     *
     * @param kinds      판정할 피드백 종류
     * @param subsetSize 두 번째 판정자 항목 수 (0이면 만들지 않음)
     * @return 판정 항목 수
     */
    public static int export(Map<String, Path> logsByVersion, Path outDir, long seed, Set<Kind> kinds, int subsetSize)
            throws IOException {
        if (Files.exists(outDir)) throw new IllegalStateException("출력 폴더가 이미 있습니다 (판정 시트 보호): " + outDir);

        record Item(String version, Sample sample, String prompt) {}
        List<Item> items = new ArrayList<>();
        for (var e : logsByVersion.entrySet()) {
            ParsedRun run = RunLogParser.parse(e.getValue());
            if (run.samples().isEmpty()) throw new IllegalStateException("응답을 찾지 못했습니다: " + e.getValue());
            for (Sample s : run.samples()) {
                if (!kinds.contains(s.kind())) continue;
                String prompt = run.prompts().get(s.context());
                if (prompt == null) throw new IllegalStateException("프롬프트가 없습니다: " + e.getKey() + " " + s.context());
                items.add(new Item(e.getKey(), s, prompt));
            }
        }
        Collections.shuffle(items, new Random(seed));

        Path itemDir = outDir.resolve("items");
        Path keyDir = outDir.resolve("_key");
        Files.createDirectories(itemDir);
        Files.createDirectories(keyDir);

        List<List<String>> sheet = new ArrayList<>();
        sheet.add(List.of(SHEET_HEADER_ID, "구분", "응답", COL_LABEL, COL_TYPE, "메모"));
        List<List<String>> key = new ArrayList<>();
        key.add(List.of("id", "version", "kind", "context", "rep"));

        if (subsetSize > items.size()) throw new IllegalArgumentException("부분 시트 항목 수가 전체보다 큽니다: " + subsetSize);
        Set<Integer> subset =
                stratifiedSample(items.stream().map(Item::version).toList(), subsetSize, new Random(seed + 1));
        List<List<String>> subsetSheet = new ArrayList<>();
        subsetSheet.add(sheet.getFirst());
        List<Map<String, String>> fullPage = new ArrayList<>(), subsetPage = new ArrayList<>();

        int width = String.valueOf(items.size()).length();
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            String id = String.format("J%0" + Math.max(3, width) + "d", i + 1);
            String kind = it.sample().kind().label();
            sheet.add(List.of(id, kind, it.sample().text(), "", "", ""));
            Map<String, String> pageItem =
                    Map.of("id", id, "kind", kind, "response", it.sample().text(), "prompt", it.prompt());
            fullPage.add(pageItem);
            if (subset.contains(i)) {
                subsetSheet.add(List.of(id, kind, it.sample().text(), "", "", ""));
                subsetPage.add(pageItem);
            }
            key.add(List.of(
                    id,
                    it.version(),
                    kind,
                    it.sample().context(),
                    String.valueOf(it.sample().rep())));
            Files.writeString(
                    itemDir.resolve(id + ".md"),
                    "# " + id + " (" + kind + ")\n\n## 응답\n\n" + it.sample().text()
                            + "\n\n## 분석 재료 (이 응답을 만든 프롬프트)\n\n```\n" + it.prompt() + "\n```\n",
                    StandardCharsets.UTF_8);
        }
        Csv.write(outDir.resolve(FULL_SHEET), sheet);
        writePage(outDir.resolve(pageName(FULL_SHEET)), "판정자1", fullPage);
        if (subsetSize > 0) {
            Csv.write(outDir.resolve(SUBSET_SHEET), subsetSheet);
            writePage(outDir.resolve(pageName(SUBSET_SHEET)), "판정자2", subsetPage);
        }
        Csv.write(keyDir.resolve("key.csv"), key);
        return items.size();
    }

    /**
     * 내보낸 폴더의 {@code items/}에서 지정한 항목만 읽어 판정 화면용으로 만든다. 일부 항목만 다시 판정할 때 쓴다 (버전 정보는 읽지 않음).
     */
    static List<Map<String, String>> readItems(Path exportDir, List<String> ids) throws IOException {
        List<Map<String, String>> items = new ArrayList<>();
        for (String id : ids) {
            String md = Files.readString(exportDir.resolve("items").resolve(id + ".md"), StandardCharsets.UTF_8);
            String kind = md.substring(md.indexOf('(') + 1, md.indexOf(')'));
            String head = "## 응답\n\n";
            String response = md.substring(md.indexOf(head) + head.length(), md.indexOf("\n\n## 분석 재료"));
            String prompt = md.substring(md.indexOf("```\n") + 4, md.lastIndexOf("\n```"));
            items.add(Map.of("id", id, "kind", kind, "response", response, "prompt", prompt));
        }
        return items;
    }

    static String pageName(String sheet) {
        return sheet.replace(".csv", ".html");
    }

    /**
     * 판정 화면(HTML 한 파일)을 만든다. 응답과 프롬프트를 나란히 보여 주고, 판정은 브라우저에 자동 저장했다가 시트와 같은 형식의 CSV로 내려받는다.
     * 외부 리소스를 불러오지 않으므로 파일을 그대로 열면 된다.
     */
    static void writePage(Path path, String title, List<Map<String, String>> items) throws IOException {
        String template;
        try (var in = BlindJudging.class.getResourceAsStream("/eval/judging-page.html")) {
            if (in == null) throw new IllegalStateException("판정 화면 템플릿이 없습니다: /eval/judging-page.html");
            template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        // <script> 안에 넣는 JSON — 응답에 "</script>"가 있어도 태그가 닫히지 않도록 "</"를 이스케이프
        String json =
                JSON.writeValueAsString(Map.of("title", title, "items", items)).replace("</", "<\\/");
        Files.writeString(path, template.replace("__TITLE__", title).replace("__DATA__", json), StandardCharsets.UTF_8);
    }

    /**
     * 그룹(버전)별 비율대로 {@code size}개 인덱스를 고른다. 비율의 소수 부분은 큰 쪽부터 1개씩 더 준다 (최대 나머지 방식).
     * 버전마다 판정자 2가 보는 항목 수가 고르게 돼, 버전별 일치도도 볼 수 있다.
     */
    static Set<Integer> stratifiedSample(List<String> groupOfIndex, int size, Random rnd) {
        Map<String, List<Integer>> byGroup = new LinkedHashMap<>();
        for (int i = 0; i < groupOfIndex.size(); i++) {
            byGroup.computeIfAbsent(groupOfIndex.get(i), g -> new ArrayList<>()).add(i);
        }
        int total = groupOfIndex.size();
        Map<String, Integer> quota = new LinkedHashMap<>();
        Map<String, Double> remainder = new LinkedHashMap<>();
        int assigned = 0;
        for (var e : byGroup.entrySet()) {
            double exact = (double) size * e.getValue().size() / total;
            quota.put(e.getKey(), (int) Math.floor(exact));
            remainder.put(e.getKey(), exact - Math.floor(exact));
            assigned += (int) Math.floor(exact);
        }
        List<String> byRemainder = new ArrayList<>(remainder.keySet());
        byRemainder.sort((a, b) -> Double.compare(remainder.get(b), remainder.get(a)));
        for (int i = 0; i < size - assigned; i++) quota.merge(byRemainder.get(i), 1, Integer::sum);

        Set<Integer> picked = new TreeSet<>();
        for (var e : byGroup.entrySet()) {
            List<Integer> pool = new ArrayList<>(e.getValue());
            Collections.shuffle(pool, rnd);
            picked.addAll(pool.subList(0, quota.get(e.getKey())));
        }
        return picked;
    }

    // ── score ──

    /**
     * 판정 결과를 집계해 마크다운 보고서를 만든다.
     *
     * @param sheets 판정자별 시트 (1개 이상). 2개 이상이면 앞의 두 시트로 κ를 계산한다.
     * @param fin    불일치를 합의한 최종 시트 (없으면 null)
     */
    public static String score(Path keyCsv, List<Path> sheets, Path fin) throws IOException {
        Map<String, KeyRow> key = readKey(keyCsv);
        List<String> versions =
                key.values().stream().map(KeyRow::version).distinct().toList();

        StringBuilder r = new StringBuilder("# 블라인드 판정 집계\n\n");
        r.append(String.format("항목 %d개, 버전 %s%n%n", key.size(), versions));

        List<Map<String, Judgement>> judged = new ArrayList<>();
        for (Path s : sheets) judged.add(readSheet(s, key));

        for (int j = 0; j < sheets.size(); j++) {
            appendJudge(r, "판정자 " + (j + 1) + " (" + sheets.get(j).getFileName() + ")", judged.get(j), key, versions);
        }
        if (judged.size() >= 2) appendAgreement(r, judged.get(0), judged.get(1));
        if (fin != null) appendJudge(r, "최종 (합의)", readSheet(fin, key), key, versions);
        return r.toString();
    }

    private static void appendJudge(
            StringBuilder r, String title, Map<String, Judgement> j, Map<String, KeyRow> key, List<String> versions) {
        long missing = key.keySet().stream()
                .filter(id -> !j.containsKey(id) || j.get(id).error() == null)
                .count();
        r.append("## ").append(title).append("\n\n");
        if (missing > 0) r.append(String.format("판정하지 않은 항목 %d개는 집계에서 뺐습니다.%n%n", missing));

        r.append("| 버전 | 구분 | 틀림 | 비율 | 95% 구간 (Wilson) | 95% 구간 (주차 단위 부트스트랩) |\n|---|---|---|---|---|---|\n");
        Map<String, int[]> totals = new LinkedHashMap<>();
        for (String v : versions) {
            for (String kind : List.of("주간", "월말", "전체")) {
                Map<String, int[]> byContext = new TreeMap<>();
                for (KeyRow k : key.values()) {
                    if (!k.version().equals(v)
                            || !(kind.equals("전체") || k.kind().equals(kind))) continue;
                    Judgement jd = j.get(k.id());
                    if (jd == null || jd.error() == null) continue;
                    int[] c = byContext.computeIfAbsent(k.kind() + "|" + k.context(), x -> new int[2]);
                    if (jd.error()) c[0]++;
                    c[1]++;
                }
                int err = byContext.values().stream().mapToInt(c -> c[0]).sum();
                int n = byContext.values().stream().mapToInt(c -> c[1]).sum();
                if (n == 0) continue;
                Interval w = JudgingStats.wilson(err, n);
                Interval b =
                        JudgingStats.clusterBootstrap(new ArrayList<>(byContext.values()), BOOTSTRAP_ITERATIONS, 140);
                r.append(String.format(
                        "| %s | %s | %d/%d | %.1f%% | %s | %s |%n",
                        v, kind, err, n, 100.0 * err / n, JudgingStats.pct(w), JudgingStats.pct(b)));
                if (kind.equals("전체")) totals.put(v, new int[] {err, n});
            }
        }

        if (totals.size() >= 2) {
            r.append("\n버전 간 차이 (전체, Fisher 정확 검정 양측 — 같은 주차 반복 응답이라 p값은 낙관적)\n\n");
            List<String> vs = new ArrayList<>(totals.keySet());
            for (int a = 0; a < vs.size(); a++) {
                for (int b = a + 1; b < vs.size(); b++) {
                    int[] x = totals.get(vs.get(a)), y = totals.get(vs.get(b));
                    double p = JudgingStats.fisherTwoSided(x[0], x[1] - x[0], y[0], y[1] - y[0]);
                    r.append(String.format("- %s vs %s: p = %.3g%n", vs.get(a), vs.get(b), p));
                }
            }
        }

        Map<String, Map<String, Integer>> types = new LinkedHashMap<>();
        for (KeyRow k : key.values()) {
            Judgement jd = j.get(k.id());
            if (jd == null || !Boolean.TRUE.equals(jd.error()) || jd.types().isBlank()) continue;
            for (String t : jd.types().split("[,;/]")) {
                if (t.isBlank()) continue;
                types.computeIfAbsent(t.strip(), x -> new TreeMap<>()).merge(k.version(), 1, Integer::sum);
            }
        }
        if (!types.isEmpty()) {
            r.append("\n오류 유형 (버전별 건수, 한 응답에 여러 유형 가능)\n\n");
            types.forEach(
                    (t, m) -> r.append("- ").append(t).append(": ").append(m).append('\n'));
        }
        r.append('\n');
    }

    private static void appendAgreement(StringBuilder r, Map<String, Judgement> a, Map<String, Judgement> b) {
        List<Boolean> x = new ArrayList<>(), y = new ArrayList<>();
        List<String> disagree = new ArrayList<>();
        for (var e : a.entrySet()) {
            Judgement other = b.get(e.getKey());
            if (e.getValue().error() == null || other == null || other.error() == null) continue;
            x.add(e.getValue().error());
            y.add(other.error());
            if (!e.getValue().error().equals(other.error())) disagree.add(e.getKey());
        }
        r.append("## 판정자 일치도\n\n");
        if (x.isEmpty()) {
            r.append("두 판정자가 함께 판정한 항목이 없습니다.\n\n");
            return;
        }
        double kappa = JudgingStats.cohenKappa(x, y);
        r.append(String.format(
                "함께 판정 %d개, 일치 %d개 (%.1f%%), Cohen's κ = %s%n%n",
                x.size(),
                x.size() - disagree.size(),
                100.0 * (x.size() - disagree.size()) / x.size(),
                Double.isNaN(kappa) ? "계산 불가 (판정이 한쪽으로만 몰림)" : String.format("%.2f", kappa)));
        Collections.sort(disagree);
        r.append("불일치 항목 (합의 대상): ")
                .append(disagree.isEmpty() ? "없음" : String.join(", ", disagree))
                .append("\n\n");
    }

    /**
     * 두 시트를 겹치는 항목으로 비교한다. 버전 정보 없이 계산하므로 판정기를 조정하는 동안 결과를 미리 보지 않게 해 준다.
     * 불일치 항목에는 비교 시트(판정기)의 유형·메모를 붙여 사람이 원인을 볼 수 있게 한다.
     */
    static String agreement(Map<String, Judgement> base, Map<String, Judgement> other) {
        StringBuilder r = new StringBuilder("# 판정 일치도 (버전 정보 없음)\n\n");
        appendAgreement(r, base, other);
        int both = 0, xx = 0, xo = 0, ox = 0, oo = 0;
        for (var e : base.entrySet()) {
            Judgement o = other.get(e.getKey());
            if (e.getValue().error() == null || o == null || o.error() == null) continue;
            both++;
            boolean a = e.getValue().error(), b = o.error();
            if (a && b) xx++;
            else if (a) xo++;
            else if (b) ox++;
            else oo++;
        }
        r.append(String.format(
                "| | 비교 X | 비교 O |%n|---|---|---|%n| 기준 X | %d | %d |%n| 기준 O | %d | %d |%n%n", xx, xo, ox, oo));
        if (xx + xo > 0) r.append(String.format("기준이 X인 것 중 비교도 X (재현율): %d/%d%n", xx, xx + xo));
        if (xx + ox > 0) r.append(String.format("비교가 X인 것 중 기준도 X (정밀도): %d/%d%n%n", xx, xx + ox));
        r.append("## 불일치 상세\n\n");
        base.keySet().stream().sorted().forEach(id -> {
            Judgement a = base.get(id), b = other.get(id);
            if (a.error() == null || b == null || b.error() == null || a.error().equals(b.error())) return;
            r.append(String.format(
                    "- %s 기준 %s / 비교 %s [%s] %s%n",
                    id, a.error() ? "X" : "O", b.error() ? "X" : "O", b.types(), b.memo()));
        });
        return r.toString();
    }

    // ── 입출력 ──

    static Map<String, KeyRow> readKey(Path keyCsv) throws IOException {
        List<List<String>> rows = Csv.read(keyCsv);
        Map<String, KeyRow> key = new LinkedHashMap<>();
        for (List<String> row : rows.subList(1, rows.size())) {
            if (row.size() < 5 || row.get(0).isBlank()) continue;
            key.put(
                    row.get(0),
                    new KeyRow(row.get(0), row.get(1), row.get(2), row.get(3), Integer.parseInt(row.get(4))));
        }
        return key;
    }

    static Map<String, Judgement> readSheet(Path sheet, Map<String, KeyRow> key) throws IOException {
        List<List<String>> rows = Csv.read(sheet);
        if (rows.isEmpty()) throw new IllegalStateException("빈 시트: " + sheet);
        List<String> header = rows.get(0);
        int idCol = header.indexOf(SHEET_HEADER_ID),
                labelCol = header.indexOf(COL_LABEL),
                typeCol = header.indexOf(COL_TYPE),
                memoCol = header.indexOf("메모");
        if (idCol < 0 || labelCol < 0) throw new IllegalStateException("시트 머리글에 id·판정 열이 없습니다: " + sheet);

        Map<String, Judgement> out = new LinkedHashMap<>();
        Set<String> unknown = new LinkedHashSet<>();
        for (List<String> row : rows.subList(1, rows.size())) {
            String id = cell(row, idCol);
            if (id.isBlank()) continue;
            if (key != null && !key.containsKey(id)) {
                unknown.add(id);
                continue;
            }
            out.put(
                    id,
                    new Judgement(
                            id,
                            parseLabel(cell(row, labelCol), id),
                            typeCol >= 0 ? cell(row, typeCol) : "",
                            memoCol >= 0 ? cell(row, memoCol) : ""));
        }
        if (!unknown.isEmpty()) throw new IllegalStateException("키에 없는 id: " + unknown + " (" + sheet + ")");
        return out;
    }

    /** O·맞음 → false(맞음), X·틀림 → true(틀림), 빈칸 → null(미판정). 그 외 값은 오타로 보고 거부한다. */
    static Boolean parseLabel(String v, String id) {
        String s = v.strip().toUpperCase();
        if (s.isEmpty()) return null;
        return switch (s) {
            case "O", "0", "맞음" -> false;
            case "X", "틀림" -> true;
            default -> throw new IllegalArgumentException("판정 값은 O 또는 X: " + id + " = '" + v + "'");
        };
    }

    private static String cell(List<String> row, int i) {
        return i < row.size() ? row.get(i) : "";
    }

    private static List<String> split(String v) {
        List<String> out = new ArrayList<>();
        for (String s : v.split(";")) if (!s.isBlank()) out.add(s.strip());
        return out;
    }

    private static String require(Map<String, String> opt, String name) {
        String v = opt.get(name);
        if (v == null || v.isBlank()) throw new IllegalArgumentException("-P" + name + " 필요");
        return v;
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> opt = new LinkedHashMap<>();
        for (String a : args) {
            int eq = a.indexOf('=');
            if (eq > 0) opt.put(a.substring(0, eq), a.substring(eq + 1));
        }
        return opt;
    }

    /** RFC 4180 CSV. 엑셀에서 한글이 깨지지 않도록 UTF-8 BOM을 붙여 쓰고, 읽을 때 BOM을 무시한다. */
    static final class Csv {
        private Csv() {}

        static void write(Path path, List<List<String>> rows) throws IOException {
            StringBuilder sb = new StringBuilder("﻿");
            for (List<String> row : rows) {
                for (int i = 0; i < row.size(); i++) {
                    if (i > 0) sb.append(',');
                    String v = row.get(i);
                    if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
                        sb.append('"').append(v.replace("\"", "\"\"")).append('"');
                    } else {
                        sb.append(v);
                    }
                }
                sb.append("\r\n");
            }
            Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
        }

        static List<List<String>> read(Path path) throws IOException {
            String s = Files.readString(path, StandardCharsets.UTF_8);
            if (s.startsWith("﻿")) s = s.substring(1);
            List<List<String>> rows = new ArrayList<>();
            List<String> row = new ArrayList<>();
            StringBuilder cell = new StringBuilder();
            boolean quoted = false;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (quoted) {
                    if (c == '"' && i + 1 < s.length() && s.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else if (c == '"') {
                        quoted = false;
                    } else {
                        cell.append(c);
                    }
                } else if (c == '"') {
                    quoted = true;
                } else if (c == ',') {
                    row.add(cell.toString());
                    cell.setLength(0);
                } else if (c == '\n' || c == '\r') {
                    if (c == '\r' && i + 1 < s.length() && s.charAt(i + 1) == '\n') i++;
                    row.add(cell.toString());
                    cell.setLength(0);
                    rows.add(row);
                    row = new ArrayList<>();
                } else {
                    cell.append(c);
                }
            }
            if (cell.length() > 0 || !row.isEmpty()) {
                row.add(cell.toString());
                rows.add(row);
            }
            return rows;
        }
    }
}
