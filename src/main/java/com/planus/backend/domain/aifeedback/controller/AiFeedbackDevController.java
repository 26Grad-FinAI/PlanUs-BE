package com.planus.backend.domain.aifeedback.controller;

import com.planus.backend.domain.aifeedback.core.Categories;
import com.planus.backend.domain.aifeedback.entity.Expense;
import com.planus.backend.domain.aifeedback.rag.MemoVectorStore;
import com.planus.backend.domain.aifeedback.repository.ExpenseRepository;
import com.planus.backend.domain.aifeedback.service.AiFeedbackService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 개발·검증용 내부 엔드포인트.
 * ⚠️ 운영 배포 전 제거하거나 인증/내부망 제한을 걸 것.
 */
@RestController
@RequestMapping("/internal/aifeedback")
public class AiFeedbackDevController {

    private static final Logger log = LoggerFactory.getLogger(AiFeedbackDevController.class);

    private final ExpenseRepository expenseRepo;
    private final MemoVectorStore vectorStore;
    private final AiFeedbackService feedbackService;

    public AiFeedbackDevController(ExpenseRepository expenseRepo, MemoVectorStore vectorStore,
                                   AiFeedbackService feedbackService) {
        this.expenseRepo = expenseRepo;
        this.vectorStore = vectorStore;
        this.feedbackService = feedbackService;
    }

    /**
     * 기존 지출 중 메모 있는 건을 임베딩 적재(백필). 멱등 — 여러 번 호출해도 중복 적재 안 됨.
     * 신규 지출은 주간 파이프라인이 처리하므로, 이 호출은 과거 데이터용으로 한 번이면 충분.
     */
    @PostMapping("/backfill-embeddings")
    public Map<String, Object> backfillEmbeddings() {
        long before = vectorStore.count();
        List<Expense> all = expenseRepo.findAll();
        for (Expense e : all) {
            // index()가 내부에서 (1) 메모 없음 (2) 이미 적재됨 을 스킵하므로 그냥 전부 호출
            vectorStore.index(e, Categories.name(e.getCategoryId()));
        }
        long after = vectorStore.count();
        long added = after - before;
        log.info("임베딩 백필 — 지출 {}건 스캔, 신규 적재 {}건, 총 {}건", all.size(), added, after);
        return Map.of("scanned", all.size(), "added", added, "total", after);
    }

    /**
     * 주간 피드백 생성을 수동 실행(스케줄러를 기다리지 않고 검증).
     * 예: GET /internal/aifeedback/run-weekly?userId=1&weekEnd=2026-06-22
     * 조건(저축 초과/이상치/카테고리 초과) 미충족이면 생성 안 됨 → created=false.
     */
    @GetMapping("/run-weekly")
    public Map<String, Object> runWeekly(@RequestParam Long userId,
                                         @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekEnd) {
        try {
            return feedbackService.generateWeekly(userId, weekEnd)
                    .<Map<String, Object>>map(fb -> Map.of(
                            "created", true,
                            "id", fb.getId(),
                            "confidence", String.valueOf(fb.getConfidence()),
                            "adviceType", String.valueOf(fb.getAdviceType()),
                            "feedback", String.valueOf(fb.getFeedbackText())))
                    .orElse(Map.of(
                            "created", false,
                            "reason", "생성 조건 미충족(예산 초과·이상치·카테고리 초과 없음)"));
        } catch (Exception e) {
            log.error("주간 피드백 수동 실행 실패 — userId={}, weekEnd={}", userId, weekEnd, e);
            return Map.of("created", false, "error", String.valueOf(e.getMessage()));
        }
    }
}
