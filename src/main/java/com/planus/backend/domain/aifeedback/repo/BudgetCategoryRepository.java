package com.planus.backend.domain.aifeedback.repo;

import com.planus.backend.domain.aifeedback.entity.BudgetCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface BudgetCategoryRepository extends JpaRepository<BudgetCategory, Long> {
    List<BudgetCategory> findByBudgetId(Long budgetId);
}
