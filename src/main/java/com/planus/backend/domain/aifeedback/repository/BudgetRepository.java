package com.planus.backend.domain.aifeedback.repository;

import com.planus.backend.domain.aifeedback.entity.Budget;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 월별 예산 리포지토리. */
public interface BudgetRepository extends JpaRepository<Budget, Long> {

    /** 사용자의 특정 월 예산을 조회한다. */
    Optional<Budget> findByUserIdAndYearMonth(Long userId, LocalDate yearMonth);

    /** 사용자의 특정 월 예산 존재 여부를 확인한다. 페이싱 과거 월 필터용. */
    boolean existsByUserIdAndYearMonth(Long userId, LocalDate yearMonth);
}
