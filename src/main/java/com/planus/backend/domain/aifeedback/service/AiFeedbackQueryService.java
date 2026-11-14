package com.planus.backend.domain.aifeedback.service;

import com.planus.backend.domain.aifeedback.dto.WeeklyFeedbackResponse;
import com.planus.backend.domain.aifeedback.repository.AiFeedbackRepository;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 저장된 주간 AI 피드백 조회. 피드백은 주간 배치가 생성하므로 여기서는 DB만 읽고 LLM을 호출하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class AiFeedbackQueryService {

    private static final String WEEKLY = "WEEKLY";

    private final AiFeedbackRepository feedbackRepo;

    /**
     * 주간 피드백을 조회한다.
     *
     * @param userId  사용자 ID
     * @param weekEnd 조회할 주의 마지막 날(일요일). null이면 가장 최근 주
     * @return 피드백, 없으면 {@code exists=false}
     */
    @Transactional(readOnly = true)
    public WeeklyFeedbackResponse getWeekly(Long userId, LocalDate weekEnd) {
        if (weekEnd == null) {
            return feedbackRepo.findByUserIdAndPeriodTypeOrderByPeriodStartDesc(userId, WEEKLY).stream()
                    .findFirst()
                    .map(WeeklyFeedbackResponse::from)
                    .orElseGet(WeeklyFeedbackResponse::empty);
        }
        return feedbackRepo
                .findByUserIdAndPeriodTypeAndPeriodStartAndPeriodEnd(userId, WEEKLY, weekEnd.minusDays(6), weekEnd)
                .map(WeeklyFeedbackResponse::from)
                .orElseGet(WeeklyFeedbackResponse::empty);
    }
}
