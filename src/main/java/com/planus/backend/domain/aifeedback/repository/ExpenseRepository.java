package com.planus.backend.domain.aifeedback.repository;

import com.planus.backend.domain.aifeedback.entity.Expense;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.List;

/** 거래 내역 리포지토리. */
public interface ExpenseRepository extends JpaRepository<Expense, Long> {

    /** 특정 기간 내 사용자 거래 목록을 조회한다. */
    List<Expense> findByUserIdAndExpenseDateBetween(Long userId, LocalDateTime from, LocalDateTime to);

    /** 특정 시점 이전 사용자 거래 목록을 조회한다. */
    List<Expense> findByUserIdAndExpenseDateBefore(Long userId, LocalDateTime before);
}
