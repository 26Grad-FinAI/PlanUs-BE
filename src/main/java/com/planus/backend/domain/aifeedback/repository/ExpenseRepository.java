package com.planus.backend.domain.aifeedback.repository;

import com.planus.backend.domain.aifeedback.entity.Expense;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** {@link Expense} 리포지토리. 거래 내역 조회 및 집계 쿼리 제공. */
public interface ExpenseRepository extends JpaRepository<Expense, Long> {

    /**
     * 특정 기간 내 사용자 거래 목록을 조회한다.
     *
     * @param userId 사용자 ID
     * @param from   조회 시작 일시 (포함)
     * @param to     조회 종료 일시 (포함)
     * @return 기간 내 거래 목록
     */
    List<Expense> findByUserIdAndExpenseDateBetween(Long userId, LocalDateTime from, LocalDateTime to);

    /**
     * 특정 시점 이전 사용자 거래 목록을 조회한다.
     *
     * @param userId 사용자 ID
     * @param before 기준 일시 (미포함)
     * @return 기준 시점 이전 거래 목록
     */
    List<Expense> findByUserIdAndExpenseDateBefore(Long userId, LocalDateTime before);

    /**
     * [5] CauseSignalAssembler용. 특정 카테고리·기간 내 거래 목록을 조회한다.
     *
     * @param userId     사용자 ID
     * @param categoryId 카테고리 ID
     * @param from       조회 시작 일시 (포함)
     * @param to         조회 종료 일시 (포함)
     * @return 해당 카테고리 기간 내 거래 목록
     */
    List<Expense> findByUserIdAndCategoryIdAndExpenseDateBetween(
            Long userId, int categoryId, LocalDateTime from, LocalDateTime to);

    /**
     * [2] PacingComparator용. 특정 기간 내 사용자 총 지출(EXPENSE) 합계를 조회한다.
     *
     * @param userId 사용자 ID
     * @param from   조회 시작 일시 (포함)
     * @param to     조회 종료 일시 (포함)
     * @return 기간 내 총 지출 합계 (원), 거래 없으면 0
     */
    @Query(
            "SELECT COALESCE(SUM(e.amount), 0) FROM Expense e WHERE e.userId = :userId AND e.type = 'EXPENSE' AND e.expenseDate BETWEEN :from AND :to")
    long sumExpensesByPeriod(Long userId, LocalDateTime from, LocalDateTime to);

    /**
     * [2] PacingComparator용. 특정 기간 내 변동 지출(반복·예정 제외) 합계를 조회한다.
     * 페이싱 비교에서 분자(지출)와 분모(예산)의 기준을 일치시키기 위해
     * 고정·예정 지출을 제외한 변동 지출만 집계한다.
     *
     * @param userId 사용자 ID
     * @param from   조회 시작 일시 (포함)
     * @param to     조회 종료 일시 (포함)
     * @return 기간 내 변동 지출 합계 (원), 거래 없으면 0
     */
    @Query(
            "SELECT COALESCE(SUM(e.amount), 0) FROM Expense e WHERE e.userId = :userId"
                    + " AND e.type = 'EXPENSE' AND e.recurring = false AND e.planned = false"
                    + " AND e.expenseDate BETWEEN :from AND :to")
    long sumVariableExpensesByPeriod(Long userId, LocalDateTime from, LocalDateTime to);

    /**
     * [1.5] ActivityGuard용. 특정 기간 내 사용자 거래(EXPENSE) 건수를 센다.
     *
     * @param userId 사용자 ID
     * @param from   조회 시작 일시 (포함)
     * @param to     조회 종료 일시 (포함)
     * @return 기간 내 거래 건수
     */
    @Query(
            "SELECT COUNT(e) FROM Expense e WHERE e.userId = :userId AND e.type = 'EXPENSE' AND e.expenseDate BETWEEN :from AND :to")
    long countByUserIdAndPeriod(Long userId, LocalDateTime from, LocalDateTime to);
}
