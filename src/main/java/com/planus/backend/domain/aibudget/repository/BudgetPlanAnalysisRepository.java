package com.planus.backend.domain.aibudget.repository;

import com.planus.backend.domain.aibudget.entity.BudgetPlanAnalysis;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** AI 분석 데이터 리포지토리. */
public interface BudgetPlanAnalysisRepository extends JpaRepository<BudgetPlanAnalysis, Long> {

    Optional<BudgetPlanAnalysis> findByBudgetId(Long budgetId);
}
