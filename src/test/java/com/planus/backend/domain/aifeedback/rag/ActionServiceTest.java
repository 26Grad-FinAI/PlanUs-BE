package com.planus.backend.domain.aifeedback.rag;

import com.planus.backend.domain.aifeedback.entity.UserReaction;
import com.planus.backend.domain.aifeedback.repo.UserReactionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * ActionService.recommend() 검증.
 *  - DB 조회 효율: 1회 조회 후 메모리에서 reason별 분류
 *  - 거부 회피 로직: DONT_WANT_REDUCE/ALREADY_KNOWN/TOO_REPETITIVE 정확성
 *  - 절감액 0원 방지: savingsGap ≤ 0 이면 액션을 반환하지 않아야 함
 */
@ExtendWith(MockitoExtension.class)
class ActionServiceTest {

    @Mock
    UserReactionRepository reactionRepo;

    @InjectMocks
    ActionService actionService;

    @Nested
    @DisplayName("DB 조회 효율")
    class QueryEfficiency {

        @Test
        @DisplayName("recommend() 호출 시 findByUserId는 1회만 실행되어야 한다")
        void recommend_shouldQueryReactionsOnlyOnce() {
            Long userId = 1L;
            Map<Integer, Long> overspend = Map.of(1, 50_000L, 6, 30_000L, 10, 20_000L);

            when(reactionRepo.findByUserId(userId)).thenReturn(List.of(
                    reaction(userId, "DONT_WANT_REDUCE", 1),
                    reaction(userId, "ALREADY_KNOWN", 6),
                    reaction(userId, "TOO_REPETITIVE", 10)
            ));

            actionService.recommend(userId, overspend, 40_000L);

            verify(reactionRepo, times(1)).findByUserId(userId);
        }

        @Test
        @DisplayName("overspend가 비어있으면 DB 조회 자체를 하지 않아야 한다")
        void recommend_emptyOverspend_shouldNotQueryReactions() {
            var result = actionService.recommend(1L, Map.of(), 40_000L);

            assertThat(result).isEmpty();
            verify(reactionRepo, never()).findByUserId(any());
        }

        @Test
        @DisplayName("거부 이력이 없는 사용자도 DB 조회는 1회로 충분하다")
        void recommend_noReactions_shouldStillQueryOnlyOnce() {
            Long userId = 2L;
            when(reactionRepo.findByUserId(userId)).thenReturn(List.of());

            actionService.recommend(userId, Map.of(8, 60_000L), 50_000L);

            verify(reactionRepo, times(1)).findByUserId(userId);
        }
    }

    @Nested
    @DisplayName("거부 회피 로직")
    class RejectionAvoidance {

        @Test
        @DisplayName("DONT_WANT_REDUCE 카테고리는 하드 제외된다")
        void dontWantReduce_shouldBeExcluded() {
            Long userId = 1L;
            // 카테고리1이 초과액 최대지만 DONT_WANT_REDUCE
            Map<Integer, Long> overspend = Map.of(1, 80_000L, 6, 30_000L);

            when(reactionRepo.findByUserId(userId)).thenReturn(List.of(
                    reaction(userId, "DONT_WANT_REDUCE", 1)
            ));

            var result = actionService.recommend(userId, overspend, 50_000L);

            assertThat(result).isPresent();
            assertThat(result.get().categoryId()).isEqualTo(6); // 1이 아닌 6이 선택
        }

        @Test
        @DisplayName("ALREADY_KNOWN 카테고리는 후순위로 밀린다")
        void alreadyKnown_shouldBePushedToLowerPriority() {
            Long userId = 1L;
            // 카테고리6이 초과액 최대이지만 ALREADY_KNOWN → 후순위
            // 카테고리8이 차순위지만 거부 이력 없음 → 선택됨
            Map<Integer, Long> overspend = Map.of(6, 80_000L, 8, 50_000L);

            when(reactionRepo.findByUserId(userId)).thenReturn(List.of(
                    reaction(userId, "ALREADY_KNOWN", 6)
            ));

            var result = actionService.recommend(userId, overspend, 60_000L);

            assertThat(result).isPresent();
            assertThat(result.get().categoryId()).isEqualTo(8);
        }

        @Test
        @DisplayName("TOO_REPETITIVE 카테고리는 선택되되 varyMessage가 true이다")
        void tooRepetitive_shouldSetVaryMessageTrue() {
            Long userId = 1L;
            Map<Integer, Long> overspend = Map.of(10, 40_000L);

            when(reactionRepo.findByUserId(userId)).thenReturn(List.of(
                    reaction(userId, "TOO_REPETITIVE", 10)
            ));

            var result = actionService.recommend(userId, overspend, 30_000L);

            assertThat(result).isPresent();
            assertThat(result.get().categoryId()).isEqualTo(10);
            assertThat(result.get().varyMessage()).isTrue();
        }

        @Test
        @DisplayName("거부 이력 없는 카테고리는 varyMessage가 false이다")
        void noRejection_shouldSetVaryMessageFalse() {
            Long userId = 1L;
            Map<Integer, Long> overspend = Map.of(8, 40_000L);

            when(reactionRepo.findByUserId(userId)).thenReturn(List.of());

            var result = actionService.recommend(userId, overspend, 30_000L);

            assertThat(result).isPresent();
            assertThat(result.get().varyMessage()).isFalse();
        }

        @Test
        @DisplayName("모든 초과 카테고리가 DONT_WANT_REDUCE이면 액션 없음")
        void allCategoriesExcluded_shouldReturnEmpty() {
            Long userId = 1L;
            Map<Integer, Long> overspend = Map.of(1, 50_000L, 6, 30_000L);

            when(reactionRepo.findByUserId(userId)).thenReturn(List.of(
                    reaction(userId, "DONT_WANT_REDUCE", 1),
                    reaction(userId, "DONT_WANT_REDUCE", 6)
            ));

            var result = actionService.recommend(userId, overspend, 40_000L);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("절감액 0원 방지")
    class ZeroAmountGuard {

        @Test
        @DisplayName("savingsGap이 음수(저축 여유)이면 절감 액션을 반환하지 않는다")
        void negativeSavingsGap_shouldReturnEmpty() {
            Long userId = 3L;
            when(reactionRepo.findByUserId(userId)).thenReturn(List.of());

            var result = actionService.recommend(userId, Map.of(10, 30_000L), -50_000L);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("savingsGap이 정확히 0이면 절감 액션을 반환하지 않는다")
        void zeroSavingsGap_shouldReturnEmpty() {
            Long userId = 4L;
            when(reactionRepo.findByUserId(userId)).thenReturn(List.of());

            var result = actionService.recommend(userId, Map.of(6, 20_000L), 0L);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("savingsGap이 양수이면 정상적으로 절감 액션을 반환한다")
        void positiveSavingsGap_shouldReturnAction() {
            Long userId = 5L;
            when(reactionRepo.findByUserId(userId)).thenReturn(List.of());

            var result = actionService.recommend(userId, Map.of(8, 40_000L), 25_000L);

            assertThat(result).isPresent();
            assertThat(result.get().amountWon()).isEqualTo(25_000L);
        }

        @Test
        @DisplayName("절감액은 min(초과액, savingsGap)이다")
        void amount_shouldBeMinOfOverspendAndGap() {
            Long userId = 6L;
            when(reactionRepo.findByUserId(userId)).thenReturn(List.of());

            // 초과액(20,000) < savingsGap(50,000) → 초과액이 절감액
            var result = actionService.recommend(userId, Map.of(8, 20_000L), 50_000L);

            assertThat(result).isPresent();
            assertThat(result.get().amountWon()).isEqualTo(20_000L);
        }
    }

    // ── 헬퍼 ──

    private static UserReaction reaction(Long userId, String reason, int categoryId) {
        return UserReaction.builder()
                .userId(userId)
                .reaction("NOT_HELPFUL")
                .rejectionReason(reason)
                .categoryId(categoryId)
                .build();
    }
}
