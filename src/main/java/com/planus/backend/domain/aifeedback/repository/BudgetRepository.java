package com.planus.backend.domain.aifeedback.repository;

import com.planus.backend.domain.aifeedback.entity.Budget;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.Optional;

/** 월별 예산 리포지토리. */
public interface BudgetRepository extends JpaRepository<Budget, Long> {

    /** 사용자의 특정 월 예산을 조회한다. */
    Optional<Budget> findByUserIdAndYearMonth(Long userId, LocalDate yearMonth);
}
