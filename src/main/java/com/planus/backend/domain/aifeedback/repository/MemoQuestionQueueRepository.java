package com.planus.backend.domain.aifeedback.repository;

import com.planus.backend.domain.aifeedback.entity.MemoQuestionQueue;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** {@link MemoQuestionQueue} 리포지토리. [3.5] 메모 질문 큐 조회·적재. */
public interface MemoQuestionQueueRepository extends JpaRepository<MemoQuestionQueue, Long> {

    /**
     * 특정 사용자의 특정 상태 질문 목록을 조회한다.
     *
     * @param userId 사용자 ID
     * @param status 질문 상태 (PENDING / ANSWERED / EXPIRED)
     * @return 해당 상태의 질문 목록
     */
    List<MemoQuestionQueue> findByUserIdAndStatus(Long userId, String status);

    /**
     * 특정 주에 대기 중인(PENDING) 질문 수를 센다. 주당 상한({@code weeklyQuestionCap}) 체크용.
     *
     * @param userId 사용자 ID
     * @param week   질문 대상 주 시작일
     * @return 대기 중인 질문 수
     */
    @Query(
            "SELECT COUNT(q) FROM MemoQuestionQueue q WHERE q.userId = :userId AND q.questionWeek = :week AND q.status = 'PENDING'")
    long countPendingForWeek(Long userId, LocalDate week);
}
