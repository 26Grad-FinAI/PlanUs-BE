package com.planus.backend.domain.aifeedback.service;

import com.planus.backend.domain.aifeedback.dto.FeedbackReactionRequest;
import com.planus.backend.domain.aifeedback.dto.FeedbackReactionResponse;
import com.planus.backend.domain.aifeedback.dto.WeeklyFeedbackResponse;
import com.planus.backend.domain.aifeedback.entity.AiFeedback;
import com.planus.backend.domain.aifeedback.entity.Reaction;
import com.planus.backend.domain.aifeedback.entity.RejectionReason;
import com.planus.backend.domain.aifeedback.entity.UserReaction;
import com.planus.backend.domain.aifeedback.repository.AiFeedbackRepository;
import com.planus.backend.domain.aifeedback.repository.UserReactionRepository;
import com.planus.backend.global.apiPayload.code.GeneralErrorCode;
import com.planus.backend.global.apiPayload.exception.GeneralException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AI 피드백 반응(도움이 됐어요 / 별로예요 + 사유) 저장.
 *
 * <p>저장한 반응은 다음 분석에서 {@code SavingsActionCalculator}의 거절 이력과 {@code ProfileUpdater}의 sensitiveAreas에
 * 반영된다. 같은 피드백·카테고리에 다시 반응하면 새로 쌓지 않고 덮어쓴다.
 */
@Service
@RequiredArgsConstructor
public class FeedbackReactionService {

    private final AiFeedbackRepository feedbackRepo;
    private final UserReactionRepository reactionRepo;
    private final Clock clock;

    @Transactional
    public FeedbackReactionResponse save(Long userId, Long feedbackId, FeedbackReactionRequest request) {
        AiFeedback feedback = feedbackRepo.findById(feedbackId).orElse(null);
        // 남의 피드백은 존재 여부를 드러내지 않도록 없는 것과 같게 응답한다
        if (feedback == null || !Objects.equals(feedback.getUserId(), userId)) {
            throw new GeneralException(GeneralErrorCode.AI_FEEDBACK_NOT_FOUND);
        }

        // 도움이 됐다면 사유는 없다. 별로라면 사유가 필수이고, 줄이고 싶지 않다면 어느 카테고리인지도 필수
        RejectionReason reason = null;
        if (request.reaction() == Reaction.NOT_HELPFUL) {
            reason = request.rejectionReason();
            if (reason == null) throw new GeneralException(GeneralErrorCode.REACTION_REASON_REQUIRED);
            if (reason == RejectionReason.DONT_WANT_REDUCE) {
                if (request.categoryId() == null)
                    throw new GeneralException(GeneralErrorCode.REACTION_CATEGORY_REQUIRED);
                // 제안받지 않은 카테고리에는 거절 이력을 만들 수 없다 (조회 응답의 actionCategoryIds 중에서만)
                if (!WeeklyFeedbackResponse.actionCategoryIds(feedback.getPayload())
                        .contains(request.categoryId()))
                    throw new GeneralException(GeneralErrorCode.REACTION_CATEGORY_NOT_SUGGESTED);
            }
        }

        LocalDateTime now = LocalDateTime.now(clock);
        final RejectionReason finalReason = reason;
        UserReaction saved = reactionRepo.findByUserIdAndFeedbackId(userId, feedbackId).stream()
                .filter(r -> Objects.equals(r.getCategoryId(), request.categoryId()))
                .findFirst()
                .map(existing -> {
                    existing.change(request.reaction(), finalReason, now);
                    return existing;
                })
                .orElseGet(() -> UserReaction.builder()
                        .feedbackId(feedbackId)
                        .userId(userId)
                        .reaction(request.reaction())
                        .rejectionReason(finalReason)
                        .categoryId(request.categoryId())
                        .createdAt(now)
                        .build());
        return FeedbackReactionResponse.from(reactionRepo.save(saved));
    }
}
