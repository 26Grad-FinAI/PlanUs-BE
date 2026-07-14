package com.planus.backend.domain.aifeedback.repository;

import com.planus.backend.domain.aifeedback.entity.AiFeedback;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** AI 피드백 저장·조회 리포지토리. */
public interface AiFeedbackRepository extends JpaRepository<AiFeedback, Long> {

    /** 동일 주차/월차 피드백 존재 여부 조회 (멱등 upsert용). */
    Optional<AiFeedback> findByUserIdAndPeriodTypeAndPeriodStartAndPeriodEnd(
            Long userId, String periodType, LocalDate periodStart, LocalDate periodEnd);

    /** 최근 피드백 조회 (periodStart 내림차순). ProfileUpdater.complianceRate 산출용. */
    List<AiFeedback> findByUserIdAndPeriodTypeOrderByPeriodStartDesc(Long userId, String periodType);
}
