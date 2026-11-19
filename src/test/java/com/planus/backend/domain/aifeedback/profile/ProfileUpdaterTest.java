package com.planus.backend.domain.aifeedback.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.planus.backend.domain.aifeedback.entity.AiFeedback;
import com.planus.backend.domain.aifeedback.entity.RejectionReason;
import com.planus.backend.domain.aifeedback.entity.UserProfile;
import com.planus.backend.domain.aifeedback.entity.UserReaction;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ProfileUpdaterTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ProfileUpdater updater = new ProfileUpdater(mapper);

    // ── repeatPatterns ──

    @Nested
    @DisplayName("repeatPatterns — streak 카운트")
    class RepeatPatternsTest {

        @Test
        @DisplayName("초과 카테고리의 streak가 1 증가한다")
        void increments_streak() throws Exception {
            UserProfile profile = profileWithPatterns("{\"1\":{\"streak\":2}}");

            updater.update(profile, Set.of(1), List.of(), List.of(), false);

            Map<String, Map<String, Object>> result = parsePatterns(profile);
            assertThat(result.get("1").get("streak")).isEqualTo(3);
        }

        @Test
        @DisplayName("신규 초과 카테고리는 streak=1로 등록된다")
        void creates_new_entry() throws Exception {
            UserProfile profile = profileWithPatterns("{}");

            updater.update(profile, Set.of(2), List.of(), List.of(), false);

            Map<String, Map<String, Object>> result = parsePatterns(profile);
            assertThat(result).containsKey("2");
            assertThat(result.get("2").get("streak")).isEqualTo(1);
        }

        @Test
        @DisplayName("비초과 카테고리는 리셋(삭제)된다")
        void resets_non_overspend() throws Exception {
            UserProfile profile = profileWithPatterns("{\"1\":{\"streak\":3},\"2\":{\"streak\":1}}");

            updater.update(profile, Set.of(1), List.of(), List.of(), false); // 2번은 비초과

            Map<String, Map<String, Object>> result = parsePatterns(profile);
            assertThat(result).containsKey("1");
            assertThat(result).doesNotContainKey("2");
        }

        @Test
        @DisplayName("연속 3주 초과 시 streak=3")
        void three_consecutive_weeks() throws Exception {
            UserProfile profile = profileWithPatterns("{}");

            // 3주 연속 호출
            updater.update(profile, Set.of(1), List.of(), List.of(), false);
            updater.update(profile, Set.of(1), List.of(), List.of(), false);
            updater.update(profile, Set.of(1), List.of(), List.of(), false);

            Map<String, Map<String, Object>> result = parsePatterns(profile);
            assertThat(result.get("1").get("streak")).isEqualTo(3);
        }

        @Test
        @DisplayName("LOW_DATA일 때 repeatPatterns 갱신 스킵 (streak 보호)")
        void lowData_skips_repeat_patterns() throws Exception {
            UserProfile profile = profileWithPatterns("{\"1\":{\"streak\":3}}");

            // isLowData=true → 빈 overspend로도 streak 유지
            updater.update(profile, Set.of(), List.of(), List.of(), true);

            Map<String, Map<String, Object>> result = parsePatterns(profile);
            assertThat(result).containsKey("1");
            assertThat(result.get("1").get("streak")).isEqualTo(3);
        }
    }

    // ── sensitiveAreas ──

    @Nested
    @DisplayName("sensitiveAreas — DONT_WANT_REDUCE 거부 반영")
    class SensitiveAreasTest {

        @Test
        @DisplayName("한 번 거절해도 등재된다 — 사용자가 줄이고 싶지 않다고 한 카테고리는 바로 제외")
        void adds_category_on_single_rejection() throws Exception {
            List<UserReaction> reactions = List.of(rejection(2, RejectionReason.DONT_WANT_REDUCE));
            UserProfile profile = profileWithSensitive("[]");

            updater.update(profile, Set.of(), reactions, List.of(), false);

            assertThat(parseSensitive(profile)).containsExactly(2L);
        }

        @Test
        @DisplayName("같은 카테고리를 여러 번 거절해도 중복 없이 한 번")
        void no_duplicates() throws Exception {
            List<UserReaction> reactions = List.of(
                    rejection(2, RejectionReason.DONT_WANT_REDUCE), rejection(2, RejectionReason.DONT_WANT_REDUCE));
            UserProfile profile = profileWithSensitive("[2]");

            updater.update(profile, Set.of(), reactions, List.of(), false);

            assertThat(parseSensitive(profile)).containsExactly(2L);
        }

        @Test
        @DisplayName("효력 있는 거절이 없으면 목록에서 빠진다 — 누적이 아니라 매번 다시 계산 (만료 반영)")
        void removed_when_no_active_rejection() throws Exception {
            UserProfile profile = profileWithSensitive("[2, 5]");

            updater.update(
                    profile, Set.of(), List.of(rejection(5, RejectionReason.DONT_WANT_REDUCE)), List.of(), false);

            assertThat(parseSensitive(profile)).containsExactly(5L);
        }

        @Test
        @DisplayName("다른 사유(이미 알아요 등)의 거절은 제외 대상이 아니다")
        void other_reasons_not_sensitive() throws Exception {
            UserProfile profile = profileWithSensitive("[]");

            updater.update(
                    profile,
                    Set.of(),
                    List.of(rejection(2, RejectionReason.ALREADY_KNOWN), rejection(3, RejectionReason.TOO_REPETITIVE)),
                    List.of(),
                    false);

            assertThat(parseSensitive(profile)).isEmpty();
        }
    }

    // ── complianceRate ──

    @Nested
    @DisplayName("complianceRate — 예산 준수율")
    class ComplianceRateTest {

        @Test
        @DisplayName("hadOverspend=false인 주만 준수로 카운트")
        void counts_by_had_overspend() {
            List<AiFeedback> feedbacks = List.of(
                    weeklyFeedback("ALERT", false, 1), // 이상치만 → 준수
                    weeklyFeedback("POSITIVE", false, 2), // 정상 → 준수
                    weeklyFeedback("ALERT", true, 3)); // 초과 → 미준수

            UserProfile profile = defaultProfile();
            updater.update(profile, Set.of(), List.of(), feedbacks, false);

            // 2/3 = 0.667
            assertThat(profile.getComplianceRate()).isCloseTo(0.667, within(0.01));
        }

        @Test
        @DisplayName("LOW_DATA는 분모에서 제외")
        void excludes_low_data() {
            List<AiFeedback> feedbacks = List.of(
                    weeklyFeedback("POSITIVE", false, 1),
                    weeklyFeedback("LOW_DATA", false, 2), // 판정 불가 → 분모 제외
                    weeklyFeedback("ALERT", true, 3));

            UserProfile profile = defaultProfile();
            updater.update(profile, Set.of(), List.of(), feedbacks, false);

            // 판정 가능: POSITIVE(준수) + ALERT(미준수) = 2주, 준수 1주 → 0.5
            assertThat(profile.getComplianceRate()).isCloseTo(0.5, within(0.01));
        }

        @Test
        @DisplayName("ALERT + hadOverspend=false → 준수 (이상치 ≠ 예산 초과)")
        void alert_without_overspend_is_compliant() {
            // 이상치가 떴지만 예산은 지킨 주
            List<AiFeedback> feedbacks = List.of(weeklyFeedback("ALERT", false, 1), weeklyFeedback("ALERT", false, 2));

            UserProfile profile = defaultProfile();
            updater.update(profile, Set.of(), List.of(), feedbacks, false);

            assertThat(profile.getComplianceRate()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("판정 가능한 피드백이 없으면 갱신하지 않는다")
        void no_update_when_all_low_data() {
            List<AiFeedback> feedbacks = List.of(weeklyFeedback("LOW_DATA", false, 1));

            UserProfile profile = defaultProfile();
            double before = profile.getComplianceRate();
            updater.update(profile, Set.of(), List.of(), feedbacks, false);

            assertThat(profile.getComplianceRate()).isEqualTo(before);
        }
    }

    // ── 헬퍼 ──

    private UserProfile defaultProfile() {
        return UserProfile.builder()
                .userId(1L)
                .repeatPatterns("{}")
                .sensitiveAreas("[]")
                .build();
    }

    private UserProfile profileWithPatterns(String patterns) {
        return UserProfile.builder()
                .userId(1L)
                .repeatPatterns(patterns)
                .sensitiveAreas("[]")
                .build();
    }

    private UserProfile profileWithSensitive(String sensitive) {
        return UserProfile.builder()
                .userId(1L)
                .repeatPatterns("{}")
                .sensitiveAreas(sensitive)
                .build();
    }

    private Map<String, Map<String, Object>> parsePatterns(UserProfile profile) throws Exception {
        return mapper.readValue(profile.getRepeatPatterns(), new TypeReference<Map<String, Map<String, Object>>>() {});
    }

    private List<Long> parseSensitive(UserProfile profile) throws Exception {
        return mapper.readValue(profile.getSensitiveAreas(), new TypeReference<List<Long>>() {});
    }

    private static AiFeedback weeklyFeedback(String feedbackType, boolean hadOverspend, int weekOffset) {
        return AiFeedback.builder()
                .periodType("WEEKLY")
                .feedbackType(feedbackType)
                .hadOverspend(hadOverspend)
                .periodStart(LocalDate.of(2026, 7, 6).minusWeeks(weekOffset))
                .build();
    }

    private static UserReaction rejection(int categoryId, RejectionReason reason) {
        return UserReaction.builder()
                .categoryId(categoryId)
                .rejectionReason(reason)
                .build();
    }
}
