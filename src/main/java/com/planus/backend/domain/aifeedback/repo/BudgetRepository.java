package com.planus.backend.domain.aifeedback.repo;

import com.planus.backend.domain.aifeedback.entity.Budget;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.Optional;

public interface BudgetRepository extends JpaRepository<Budget, Long> {
    Optional<Budget> findByUserIdAndYearMonth(Long userId, LocalDate yearMonth);
}
