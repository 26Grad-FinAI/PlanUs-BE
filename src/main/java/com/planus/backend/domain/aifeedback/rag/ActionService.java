package com.planus.backend.domain.aifeedback.rag;

import com.planus.backend.domain.aifeedback.entity.Reaction;
import com.planus.backend.domain.aifeedback.entity.RejectionReason;
import com.planus.backend.domain.aifeedback.entity.UserReaction;
import com.planus.backend.domain.aifeedback.repository.UserReactionRepository;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * [7] 맞춤 액션 + 거부 회피.
 *  - 절감 후보 = 예산 초과(예상) 카테고리, 초과액 큰 순.
 *  - 회피: DONT_WANT_REDUCE는 하드 제외 / ALREADY_KNOWN은 약한 후순위(참신성) / TOO_REPETITIVE는 카테고리 유지·표현만 다양화.
 *  - 절감액 = min(해당 카테고리 초과액, 저축 목표 갭).
 * 실험: DONT_WANT_REDUCE 회피 정확도 3/3.
 */
@Service
public class ActionService {

    private final UserReactionRepository reactionRepo;

    /** @param reactionRepo 사용자 반응 조회 리포지토리 */
    public ActionService(UserReactionRepository reactionRepo) { this.reactionRepo = reactionRepo; }

    /**
     * @param overspendWon  카테고리별 예상 초과액(원), 초과(>0)만 의미
     * @param savingsGapWon 저축 목표 갭(예상 총지출 − 가용예산), 양수면 목표 미달
     */
    public Optional<ActionPlan> recommend(Long userId, Map<Integer, Long> overspendWon, long savingsGapWon) {
        List<Map.Entry<Integer, Long>> candidates = overspendWon.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .sorted(Map.Entry.<Integer, Long>comparingByValue().reversed())
                .toList();
        if (candidates.isEmpty()) return Optional.empty(); // 절약 중 → 절감 제안 없음(긍정 피드백)

        // 1회 조회 후 메모리에서 reason별 분류
        List<UserReaction> reactions = reactionRepo.findByUserId(userId);
        Set<Integer> dontReduce = filterByReason(reactions, RejectionReason.DONT_WANT_REDUCE);
        Set<Integer> alreadyKnown = filterByReason(reactions, RejectionReason.ALREADY_KNOWN);
        Set<Integer> repetitive = filterByReason(reactions, RejectionReason.TOO_REPETITIVE);

        List<Integer> avoided = candidates.stream()
                .map(Map.Entry::getKey)
                .filter(c -> !dontReduce.contains(c))                 // 하드 제외
                .sorted(Comparator
                        .comparing((Integer c) -> alreadyKnown.contains(c)) // 이미 앎 → 후순위
                        .thenComparing(c -> -overspendWon.get(c)))
                .toList();
        if (avoided.isEmpty()) return Optional.empty();

        int top = avoided.get(0);
        long amount = Math.min(overspendWon.get(top), Math.max(savingsGapWon, 0));
        if (amount <= 0) return Optional.empty();                       // 저축 여유 있으면 절감 제안 불필요
        boolean vary = repetitive.contains(top);                       // 표현 다양화 필요
        return Optional.of(new ActionPlan(top, amount, vary, savingsGapWon, avoided));
    }

    /**
     * 거부 반응 목록에서 특정 사유에 해당하는 카테고리 ID 집합을 추출한다.
     *
     * @param reactions 사용자 반응 목록
     * @param reason    필터링할 거부 사유
     * @return 해당 사유의 카테고리 ID 집합
     */
    private Set<Integer> filterByReason(List<UserReaction> reactions, RejectionReason reason) {
        return reactions.stream()
                .filter(r -> r.getReaction() == Reaction.NOT_HELPFUL)
                .filter(r -> r.getRejectionReason() == reason)
                .map(UserReaction::getCategoryId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    public record ActionPlan(int categoryId, long amountWon, boolean varyMessage,
                             long savingsGapWon, List<Integer> rankedCandidates) {}
}
