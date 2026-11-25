package com.planus.backend.domain.aifeedback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FeedbackReactionServiceTest {

    private static final long USER = 1L;
    private static final long FEEDBACK = 42L;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-19T00:00:00Z"), ZoneId.of("UTC"));

    private AiFeedbackRepository feedbackRepo;
    private UserReactionRepository reactionRepo;
    private FeedbackReactionService service;
    private final List<UserReaction> stored = new ArrayList<>();

    @BeforeEach
    void setUp() {
        feedbackRepo = mock(AiFeedbackRepository.class);
        reactionRepo = mock(UserReactionRepository.class);
        service = new FeedbackReactionService(feedbackRepo, reactionRepo, CLOCK);
        when(feedbackRepo.findById(FEEDBACK)).thenReturn(Optional.of(feedback(USER)));
        when(reactionRepo.findByUserIdAndFeedbackId(USER, FEEDBACK)).thenAnswer(inv -> List.copyOf(stored));
        when(reactionRepo.save(any(UserReaction.class))).thenAnswer(inv -> {
            UserReaction r = inv.getArgument(0);
            if (!stored.contains(r)) stored.add(r);
            return r;
        });
    }

    private static AiFeedback feedback(long userId) {
        return AiFeedback.builder()
                .id(FEEDBACK)
                .userId(userId)
                .payload("{\"actions\":[{\"categoryId\":10},{\"categoryId\":1},{\"categoryId\":2}]}")
                .build();
    }

    private static FeedbackReactionRequest req(Reaction r, RejectionReason reason, Integer category) {
        return new FeedbackReactionRequest(r, reason, category);
    }

    @Test
    @DisplayName("줄이고 싶지 않아요 — 카테고리와 함께 저장되고 시각이 기록된다")
    void saveRejection() {
        FeedbackReactionResponse out =
                service.save(USER, FEEDBACK, req(Reaction.NOT_HELPFUL, RejectionReason.DONT_WANT_REDUCE, 10));

        assertThat(out.reaction()).isEqualTo(Reaction.NOT_HELPFUL);
        assertThat(out.rejectionReason()).isEqualTo(RejectionReason.DONT_WANT_REDUCE);
        assertThat(out.categoryId()).isEqualTo(10);
        assertThat(stored).singleElement().satisfies(r -> {
            assertThat(r.getUserId()).isEqualTo(USER);
            assertThat(r.getFeedbackId()).isEqualTo(FEEDBACK);
            assertThat(r.getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 11, 19, 0, 0));
        });
    }

    @Test
    @DisplayName("도움이 됐어요 — 사유가 와도 버린다")
    void helpfulDropsReason() {
        FeedbackReactionResponse out = service.save(USER, FEEDBACK, req(Reaction.HELPFUL, RejectionReason.OTHER, null));

        assertThat(out.rejectionReason()).isNull();
    }

    @Test
    @DisplayName("같은 피드백·카테고리에 다시 반응하면 덮어쓴다 — 쌓지 않는다")
    void overwriteSameCategory() {
        service.save(USER, FEEDBACK, req(Reaction.NOT_HELPFUL, RejectionReason.DONT_WANT_REDUCE, 10));
        service.save(USER, FEEDBACK, req(Reaction.HELPFUL, null, 10));

        assertThat(stored).singleElement().satisfies(r -> {
            assertThat(r.getReaction()).isEqualTo(Reaction.HELPFUL);
            assertThat(r.getRejectionReason()).isNull();
        });
    }

    @Test
    @DisplayName("다른 카테고리에 대한 반응은 따로 저장한다")
    void differentCategoriesKeptSeparately() {
        service.save(USER, FEEDBACK, req(Reaction.NOT_HELPFUL, RejectionReason.DONT_WANT_REDUCE, 10));
        service.save(USER, FEEDBACK, req(Reaction.NOT_HELPFUL, RejectionReason.DONT_WANT_REDUCE, 1));

        assertThat(stored).extracting(UserReaction::getCategoryId).containsExactlyInAnyOrder(10, 1);
    }

    @Test
    @DisplayName("별로예요인데 사유가 없으면 거절, 줄이고 싶지 않은데 카테고리가 없어도 거절")
    void validation() {
        assertThatThrownBy(() -> service.save(USER, FEEDBACK, req(Reaction.NOT_HELPFUL, null, null)))
                .isInstanceOfSatisfying(GeneralException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(GeneralErrorCode.REACTION_REASON_REQUIRED));
        assertThatThrownBy(() ->
                        service.save(USER, FEEDBACK, req(Reaction.NOT_HELPFUL, RejectionReason.DONT_WANT_REDUCE, null)))
                .isInstanceOfSatisfying(GeneralException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(GeneralErrorCode.REACTION_CATEGORY_REQUIRED));
        verify(reactionRepo, never()).save(any());
    }

    @Test
    @DisplayName("이 피드백에서 줄이라고 제안하지 않은 카테고리는 거절 이력을 만들 수 없다 — HELPFUL에는 적용하지 않는다")
    void rejectionCategoryMustBeSuggested() {
        assertThatThrownBy(() ->
                        service.save(USER, FEEDBACK, req(Reaction.NOT_HELPFUL, RejectionReason.DONT_WANT_REDUCE, 5)))
                .isInstanceOfSatisfying(GeneralException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(GeneralErrorCode.REACTION_CATEGORY_NOT_SUGGESTED));
        verify(reactionRepo, never()).save(any());

        // 도움이 됐어요 / 다른 사유는 카테고리 검사 대상이 아니다
        service.save(USER, FEEDBACK, req(Reaction.HELPFUL, null, 5));
        assertThat(stored).hasSize(1);
    }

    @Test
    @DisplayName("없는 피드백과 남의 피드백은 똑같이 404 — 존재 여부를 드러내지 않는다")
    void notFoundOrNotMine() {
        when(feedbackRepo.findById(7L)).thenReturn(Optional.empty());
        when(feedbackRepo.findById(8L)).thenReturn(Optional.of(feedback(999L)));

        for (long id : new long[] {7L, 8L}) {
            assertThatThrownBy(() -> service.save(USER, id, req(Reaction.HELPFUL, null, null)))
                    .isInstanceOfSatisfying(GeneralException.class, e -> assertThat(e.getErrorCode())
                            .isEqualTo(GeneralErrorCode.AI_FEEDBACK_NOT_FOUND));
        }
        verify(reactionRepo, never()).save(any());
    }

    @Test
    @DisplayName("조회 응답의 절약 카테고리 — payload의 actions에서 읽고, 읽을 수 없으면 빈 목록")
    void actionCategoryIdsFromPayload() {
        assertThat(WeeklyFeedbackResponse.actionCategoryIds("{\"actions\":[{\"categoryId\":10},{\"categoryId\":1}]}"))
                .containsExactly(10, 1);
        assertThat(WeeklyFeedbackResponse.actionCategoryIds("{}")).isEmpty();
        assertThat(WeeklyFeedbackResponse.actionCategoryIds("not json")).isEmpty();
        assertThat(WeeklyFeedbackResponse.actionCategoryIds(null)).isEmpty();
    }

    @Test
    @DisplayName("저장한 반응이 다음 분석에 반영된다 — 같은 카테고리를 두 피드백에서 거절하면 sensitiveAreas에 올라 절약 제안에서 빠진다")
    void savedReactionsReachProfileAndActions() {
        // 서로 다른 두 피드백에서 같은 카테고리(2)를 "줄이고 싶지 않아요"로 거절
        when(feedbackRepo.findById(43L))
                .thenReturn(Optional.of(AiFeedback.builder()
                        .id(43L)
                        .userId(USER)
                        .payload("{\"actions\":[{\"categoryId\":2}]}")
                        .build()));
        when(reactionRepo.findByUserIdAndFeedbackId(USER, 43L)).thenReturn(List.of());
        service.save(USER, FEEDBACK, req(Reaction.NOT_HELPFUL, RejectionReason.DONT_WANT_REDUCE, 2));
        service.save(USER, 43L, req(Reaction.NOT_HELPFUL, RejectionReason.DONT_WANT_REDUCE, 2));
        assertThat(stored).hasSize(2);

        // 프로필 갱신: 2회 이상 거절한 카테고리가 sensitiveAreas로
        com.planus.backend.domain.aifeedback.entity.UserProfile profile =
                com.planus.backend.domain.aifeedback.entity.UserProfile.builder()
                        .userId(USER)
                        .repeatPatterns("{}")
                        .sensitiveAreas("[]")
                        .build();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        new com.planus.backend.domain.aifeedback.profile.ProfileUpdater(mapper)
                .update(profile, java.util.Set.of(), stored, List.of(), false);
        assertThat(profile.getSensitiveAreas()).contains("2");

        // 절약 액션: sensitiveAreas 카테고리는 제외, 거절 이력이 있는 카테고리는 점수 감점
        var calculator = new com.planus.backend.domain.aifeedback.action.SavingsActionCalculator(
                new com.planus.backend.domain.aifeedback.config.AiFeedbackProperties());
        var plans = calculator.calculate(
                java.util.Map.of(2, 100_000L, 4, 100_000L),
                150_000,
                15,
                15,
                java.util.Map.of(2, 300_000L, 4, 300_000L),
                java.util.Map.of(),
                List.of(),
                stored,
                List.of(2L));
        assertThat(plans).extracting(p -> p.categoryId()).containsExactly(4);
    }
}
