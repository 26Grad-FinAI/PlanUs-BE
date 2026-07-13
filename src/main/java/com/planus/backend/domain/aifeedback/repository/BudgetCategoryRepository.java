package com.planus.backend.domain.aifeedback.repository;

import com.planus.backend.domain.aifeedback.entity.BudgetCategory;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** 카테고리별 예산 배분 리포지토리. */
public interface BudgetCategoryRepository extends JpaRepository<BudgetCategory, Long> {

    /** 특정 예산에 속한 카테고리별 배분 목록을 조회한다. */
    List<BudgetCategory> findByBudgetId(Long budgetId);
}
