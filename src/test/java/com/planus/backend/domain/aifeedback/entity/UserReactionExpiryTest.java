package com.planus.backend.domain.aifeedback.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 거절 반응은 남긴 지 N개월이 지나면 만료된다 (#141). */
class UserReactionExpiryTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 19, 9, 0);

    private static UserReaction at(LocalDateTime createdAt) {
        return UserReaction.builder()
                .reaction(Reaction.NOT_HELPFUL)
                .rejectionReason(RejectionReason.DONT_WANT_REDUCE)
                .categoryId(10)
                .createdAt(createdAt)
                .build();
    }

    @Test
    @DisplayName("3개월 이내는 효력 있음, 경계(정확히 3개월)까지 포함, 1초라도 지나면 만료")
    void boundary() {
        assertThat(at(NOW.minusMonths(1)).isActiveAt(NOW, 3)).isTrue();
        assertThat(at(NOW.minusMonths(3)).isActiveAt(NOW, 3)).isTrue();
        assertThat(at(NOW.minusMonths(3).minusSeconds(1)).isActiveAt(NOW, 3)).isFalse();
        assertThat(at(NOW.minusMonths(4)).isActiveAt(NOW, 3)).isFalse();
    }

    @Test
    @DisplayName("시각을 모르는 이전 데이터는 효력 있음으로 본다 — 사용자의 거절을 함부로 풀지 않는다")
    void unknownTimeIsActive() {
        assertThat(at(null).isActiveAt(NOW, 3)).isTrue();
    }

    @Test
    @DisplayName("다시 반응하면 시각이 갱신되어 만료가 새로 시작된다")
    void changeRestartsExpiry() {
        UserReaction r = at(NOW.minusMonths(5));
        assertThat(r.isActiveAt(NOW, 3)).isFalse();

        r.change(Reaction.NOT_HELPFUL, RejectionReason.DONT_WANT_REDUCE, NOW);

        assertThat(r.isActiveAt(NOW.plusMonths(2), 3)).isTrue();
    }
}
