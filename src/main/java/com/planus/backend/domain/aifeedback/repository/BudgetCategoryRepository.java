package com.planus.backend.domain.aifeedback.repository;

import com.planus.backend.domain.aifeedback.entity.BudgetCategory;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 카테고리별 예산 배분 리포지토리. */
public interface BudgetCategoryRepository extends JpaRepository<BudgetCategory, Long> {

    /** 특정 예산에 속한 카테고리별 배분 목록을 조회한다. */
    List<BudgetCategory> findByBudgetId(Long budgetId);

    /** 특정 예산의 특정 카테고리 배분을 조회한다. */
    Optional<BudgetCategory> findByBudgetIdAndCategoryId(Long budgetId, Integer categoryId);
}
