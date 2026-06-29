package com.planus.backend.domain.aifeedback.repo;

import com.planus.backend.domain.aifeedback.entity.Expense;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.List;

public interface ExpenseRepository extends JpaRepository<Expense, Long> {
    List<Expense> findByUserIdAndExpenseDateBetween(Long userId, LocalDateTime from, LocalDateTime to);
    List<Expense> findByUserIdAndExpenseDateBefore(Long userId, LocalDateTime before);
}
