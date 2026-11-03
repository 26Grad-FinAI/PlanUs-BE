package com.planus.backend.domain.savingssimulation.repository;

import com.planus.backend.domain.savingssimulation.entity.SavingSimulation;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 절약 시뮬레이션 리포지토리. */
public interface SavingSimulationRepository extends JpaRepository<SavingSimulation, Long> {

    /** 해당 사용자·피드백에 대해 적용 완료된 시뮬레이션을 조회한다. */
    Optional<SavingSimulation> findByUserIdAndFeedbackIdAndIsAppliedTrue(Long userId, Long feedbackId);
}
