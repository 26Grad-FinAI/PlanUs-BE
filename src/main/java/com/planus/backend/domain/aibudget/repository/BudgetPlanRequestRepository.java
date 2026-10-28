package com.planus.backend.domain.aibudget.repository;

import com.planus.backend.domain.aibudget.entity.BudgetPlanRequest;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** AI 예산 산출 요청 리포지토리. */
public interface BudgetPlanRequestRepository extends JpaRepository<BudgetPlanRequest, Long> {

    Optional<BudgetPlanRequest> findByUserIdAndYearMonth(Long userId, LocalDate yearMonth);
}
