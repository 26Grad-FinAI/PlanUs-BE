package com.planus.backend.domain.aifeedback.profile;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.planus.backend.domain.aifeedback.entity.AiFeedback;
import com.planus.backend.domain.aifeedback.entity.RejectionReason;
import com.planus.backend.domain.aifeedback.entity.UserProfile;
import com.planus.backend.domain.aifeedback.entity.UserReaction;
import java.util.*;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * [8] 프로필 갱신기.
 *
 * <p>매주 파이프라인 마지막에 호출되어 사용자 프로필을 갱신한다.
 *
 * <p>갱신 규칙:
 * <ul>
 *   <li><b>repeatPatterns</b>: 이번 주 초과 카테고리는 streak +1, 비초과는 리셋(삭제).
 *       streak 값 자체가 페이로드에 담기고 [5]/[7]에서 판단한다.
 *   <li><b>sensitiveAreas</b>: DONT_WANT_REDUCE 2회+ 거부된 categoryId를 추가한다.
 *       현재 제거 경로 없음 — v1.1에서 설정 화면 해제 기능 추가 예정.
 *       해제 기능 구현 시, 거부 이력이 남아 있으면 다시 자동 등재되므로
 *       명시적 해제 플래그를 별도로 관리해야 한다.
 *   <li><b>complianceRate</b>: 최근 12주 피드백 중 판정 가능한 주(LOW_DATA 제외)에서
 *       예산 초과가 없던 주(hadOverspend=false)의 비율을 계산한다.
 *       POSITIVE/ALERT 기준이 아닌 실제 예산 초과 여부 기준.
 * </ul>
 */
@Component
public class ProfileUpdater {

    private static final Logger log = LoggerFactory.getLogger(ProfileUpdater.class);

    private static final int DONT_WANT_REDUCE_THRESHOLD = 2;
    private static final int COMPLIANCE_WINDOW_WEEKS = 12;

    private final ObjectMapper objectMapper;

    public ProfileUpdater(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 사용자 프로필을 갱신한다.
     *
     * @param profile             갱신 대상 프로필 (변경 후 save는 호출 측 책임)
     * @param overspendCategories 이번 주 초과 카테고리 ID 목록
     * @param reactions           전체 거부 이력
     * @param recentFeedbacks     최근 12주 주간 피드백 (complianceRate 산출용)
     * @param isLowData           이번 주 LOW_DATA 여부. true면 repeatPatterns 갱신 스킵.
     *                            기록 부족으로 판정을 못 한 것이지 예산을 지킨 게 아니므로
     *                            빈 overspend로 기존 streak를 리셋하면 안 된다.
     */
    public void update(
            UserProfile profile,
            Set<Integer> overspendCategories,
            List<UserReaction> reactions,
            List<AiFeedback> recentFeedbacks,
            boolean isLowData) {

        if (!isLowData) {
            updateRepeatPatterns(profile, overspendCategories);
        }
        updateSensitiveAreas(profile, reactions);
        updateComplianceRate(profile, recentFeedbacks);
    }

    /**
     * 초과 카테고리의 streak를 갱신한다.
     *
     * <p>이번 주에 초과된 카테고리는 streak +1, 초과되지 않은 카테고리는 리셋(삭제).
     * streak 임계 판단은 여기서 하지 않는다 — [5]가 값을 읽고 [7]이 문구를 결정한다.
     */
    private void updateRepeatPatterns(UserProfile profile, Set<Integer> overspendCategories) {
        Map<String, Map<String, Object>> patterns = parseRepeatPatterns(profile);

        // 기존 패턴 중 이번 주 초과가 아닌 것 → 리셋(삭제)
        // parseIntSafe 실패 시 -1 → overspendCategories에 없으므로 삭제됨 (손상 데이터 정리)
        patterns.keySet().removeIf(key -> !overspendCategories.contains(parseIntSafe(key)));

        // 이번 주 초과 카테고리 → streak 증가/신규 등록
        for (int categoryId : overspendCategories) {
            String key = String.valueOf(categoryId);
            Map<String, Object> entry = patterns.computeIfAbsent(key, k -> new HashMap<>());
            Object streak = entry.get("streak");
            int current = (streak instanceof Number n) ? n.intValue() : 0;
            entry.put("streak", current + 1);
        }

        try {
            profile.updateRepeatPatterns(objectMapper.writeValueAsString(patterns));
        } catch (Exception e) {
            log.warn("프로필 repeat_patterns 직렬화 실패. userId={}", profile.getUserId(), e);
        }
    }

    /**
     * DONT_WANT_REDUCE 거부가 {@value DONT_WANT_REDUCE_THRESHOLD}회+ 된 카테고리를
     * sensitiveAreas에 추가한다.
     *
     * <p>현재 제거 경로 없음 — v1.1에서 설정 화면 해제 기능 추가 예정.
     */
    private void updateSensitiveAreas(UserProfile profile, List<UserReaction> reactions) {
        Map<Integer, Long> dontWantCount = reactions.stream()
                .filter(r -> r.getRejectionReason() == RejectionReason.DONT_WANT_REDUCE)
                .filter(r -> r.getCategoryId() != null)
                .collect(Collectors.groupingBy(UserReaction::getCategoryId, Collectors.counting()));

        Set<Long> currentSensitive = parseSensitiveAreas(profile);

        for (Map.Entry<Integer, Long> entry : dontWantCount.entrySet()) {
            if (entry.getValue() >= DONT_WANT_REDUCE_THRESHOLD) {
                currentSensitive.add(entry.getKey().longValue());
            }
        }

        try {
            profile.updateSensitiveAreas(objectMapper.writeValueAsString(new ArrayList<>(currentSensitive)));
        } catch (Exception e) {
            log.warn("프로필 sensitive_areas 직렬화 실패. userId={}", profile.getUserId(), e);
        }
    }

    /**
     * 최근 12주 주간 피드백에서 예산 준수율을 계산한다.
     *
     * <p>LOW_DATA는 "판정 불가"이므로 분모에서 제외한다.
     * 준수 여부는 feedbackType(POSITIVE/ALERT)이 아닌 {@code hadOverspend} 플래그로 판정한다.
     * 이상치 탐지(ALERT)와 예산 초과는 별개 개념이기 때문이다.
     */
    private void updateComplianceRate(UserProfile profile, List<AiFeedback> recentFeedbacks) {
        List<AiFeedback> weeklyFeedbacks = recentFeedbacks.stream()
                .filter(f -> "WEEKLY".equals(f.getPeriodType()))
                .sorted(Comparator.comparing(AiFeedback::getPeriodStart).reversed())
                .limit(COMPLIANCE_WINDOW_WEEKS)
                .toList();

        // LOW_DATA 제외 — 판정 가능한 주만 분모에 포함
        List<AiFeedback> judgedFeedbacks = weeklyFeedbacks.stream()
                .filter(f -> !"LOW_DATA".equals(f.getFeedbackType()))
                .toList();

        if (judgedFeedbacks.isEmpty()) return;

        long compliantCount =
                judgedFeedbacks.stream().filter(f -> !f.isHadOverspend()).count();

        double rate = compliantCount / (double) judgedFeedbacks.size();
        profile.updateComplianceRate(rate);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> parseRepeatPatterns(UserProfile profile) {
        try {
            return objectMapper.readValue(
                    profile.getRepeatPatterns(), new TypeReference<Map<String, Map<String, Object>>>() {});
        } catch (Exception e) {
            log.warn("프로필 repeat_patterns 파싱 실패. userId={}", profile.getUserId(), e);
            return new HashMap<>();
        }
    }

    private Set<Long> parseSensitiveAreas(UserProfile profile) {
        return new HashSet<>(parseSensitiveAreaIds(objectMapper, profile));
    }

    /**
     * UserProfile의 sensitiveAreas JSON을 파싱한다. 공용 헬퍼.
     *
     * @return 민감 카테고리 ID 리스트, 파싱 실패 시 빈 리스트
     */
    public static List<Long> parseSensitiveAreaIds(ObjectMapper objectMapper, UserProfile profile) {
        if (profile == null || profile.getSensitiveAreas() == null) return List.of();
        try {
            return objectMapper.readValue(profile.getSensitiveAreas(), new TypeReference<List<Long>>() {});
        } catch (Exception e) {
            log.warn("프로필 sensitive_areas 파싱 실패. userId={}", profile.getUserId(), e);
            return List.of();
        }
    }

    /** 문자열을 int로 파싱. 실패 시 -1 반환 (호출부에서 contains 검사 시 미매칭 → 삭제 대상). */
    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
