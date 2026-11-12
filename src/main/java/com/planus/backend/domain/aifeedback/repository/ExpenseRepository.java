package com.planus.backend.domain.aifeedback.repository;

import com.planus.backend.domain.aifeedback.entity.Expense;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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
    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM Expense e WHERE e.userId = :userId"
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

    /**
     * 특정 기간 내 사용자 총 수입(INCOME) 합계를 조회한다.
     *
     * @param userId 사용자 ID
     * @param from   조회 시작 일시 (포함)
     * @param to     조회 종료 일시 (포함)
     * @return 기간 내 총 수입 합계 (원), 거래 없으면 0
     */
    @Query(
            "SELECT COALESCE(SUM(e.amount), 0) FROM Expense e WHERE e.userId = :userId AND e.type = 'INCOME' AND e.expenseDate BETWEEN :from AND :to")
    long sumIncomeByPeriod(Long userId, LocalDateTime from, LocalDateTime to);

    /**
     * 특정 기간 내 카테고리별 지출 합계를 조회한다.
     *
     * @param userId 사용자 ID
     * @param from   조회 시작 일시 (포함)
     * @param to     조회 종료 일시 (포함)
     * @return [categoryId(Integer), sum(Long)] 형태의 배열 목록
     */
    @Query("SELECT e.categoryId, COALESCE(SUM(e.amount), 0) FROM Expense e"
            + " WHERE e.userId = :userId AND e.type = 'EXPENSE'"
            + " AND e.expenseDate BETWEEN :from AND :to"
            + " GROUP BY e.categoryId")
    List<Object[]> sumExpensesGroupByCategory(Long userId, LocalDateTime from, LocalDateTime to);

    /**
     * 특정 기간 내 월별 수입(INCOME) 합계를 조회한다.
     *
     * @param userId 사용자 ID
     * @param from   조회 시작 일시 (포함)
     * @param to     조회 종료 일시 (포함)
     * @return [year(Integer), month(Integer), sum(Long)] 형태의 배열 목록
     */
    @Query("SELECT YEAR(e.expenseDate), MONTH(e.expenseDate), COALESCE(SUM(e.amount), 0)"
            + " FROM Expense e WHERE e.userId = :userId AND e.type = 'INCOME'"
            + " AND e.expenseDate BETWEEN :from AND :to"
            + " GROUP BY YEAR(e.expenseDate), MONTH(e.expenseDate)"
            + " ORDER BY YEAR(e.expenseDate), MONTH(e.expenseDate)")
    List<Object[]> sumIncomeGroupByMonth(Long userId, LocalDateTime from, LocalDateTime to);

    /**
     * 특정 기간 내 월별 지출 합계를 조회한다.
     *
     * @param userId 사용자 ID
     * @param from   조회 시작 일시 (포함)
     * @param to     조회 종료 일시 (포함)
     * @return [year(Integer), month(Integer), sum(Long)] 형태의 배열 목록
     */
    @Query("SELECT YEAR(e.expenseDate), MONTH(e.expenseDate), COALESCE(SUM(e.amount), 0)"
            + " FROM Expense e WHERE e.userId = :userId AND e.type = 'EXPENSE'"
            + " AND e.expenseDate BETWEEN :from AND :to"
            + " GROUP BY YEAR(e.expenseDate), MONTH(e.expenseDate)"
            + " ORDER BY YEAR(e.expenseDate), MONTH(e.expenseDate)")
    List<Object[]> sumExpensesGroupByMonth(Long userId, LocalDateTime from, LocalDateTime to);

    /**
     * 특정 기간 내 월별·카테고리별 지출 합계를 조회한다.
     *
     * @param userId 사용자 ID
     * @param from   조회 시작 일시 (포함)
     * @param to     조회 종료 일시 (포함)
     * @return [year(Integer), month(Integer), categoryId(Integer), sum(Long)] 형태의 배열 목록
     */
    @Query("SELECT YEAR(e.expenseDate), MONTH(e.expenseDate), e.categoryId, COALESCE(SUM(e.amount), 0)"
            + " FROM Expense e WHERE e.userId = :userId AND e.type = 'EXPENSE'"
            + " AND e.expenseDate BETWEEN :from AND :to"
            + " GROUP BY YEAR(e.expenseDate), MONTH(e.expenseDate), e.categoryId"
            + " ORDER BY YEAR(e.expenseDate), MONTH(e.expenseDate), e.categoryId")
    List<Object[]> sumExpensesGroupByMonthAndCategory(Long userId, LocalDateTime from, LocalDateTime to);

    /** 지정 기간 내 사용자의 거래를 거래 일시와 ID 내림차순으로 조회한다. 종료 일시는 포함하지 않는다. */
    @Query("SELECT e FROM Expense e WHERE e.userId = :userId"
            + " AND e.expenseDate >= :from AND e.expenseDate < :to"
            + " ORDER BY e.expenseDate DESC, e.id DESC")
    List<Expense> findRecentByPeriod(Long userId, LocalDateTime from, LocalDateTime to, Pageable pageable);

    /** 같은 기간에서 커서의 거래 일시와 ID보다 뒤에 오는 거래를 조회한다. */
    @Query("SELECT e FROM Expense e WHERE e.userId = :userId"
            + " AND e.expenseDate >= :from AND e.expenseDate < :to"
            + " AND (e.expenseDate < :cursorDate OR (e.expenseDate = :cursorDate AND e.id < :cursorId))"
            + " ORDER BY e.expenseDate DESC, e.id DESC")
    List<Expense> findRecentByPeriodAfterCursor(
            Long userId,
            LocalDateTime from,
            LocalDateTime to,
            LocalDateTime cursorDate,
            long cursorId,
            Pageable pageable);

    /**
     * 원본 반복 지출을 조회한다. sourceExpenseId가 null인 recurring 지출만 대상.
     *
     * @param recurring recurring 여부 (true)
     * @param type      거래 유형 ("EXPENSE")
     * @return 원본 반복 지출 목록
     */
    List<Expense> findByRecurringAndTypeAndSourceExpenseIdIsNull(boolean recurring, String type);

    /**
     * 원본 지출의 가장 최근 복사본을 생성 순서(id) 기준으로 조회한다.
     * 복사본이 없으면 null을 반환한다.
     */
    @Query("SELECT e FROM Expense e WHERE e.sourceExpenseId = :sourceId ORDER BY e.id DESC LIMIT 1")
    Expense findLatestCopyBySourceExpenseId(Long sourceId);

    /**
     * 특정 원본 지출에 대해 지정 기간 내 자동 등록된 반복 지출이 존재하는지 확인한다.
     *
     * @param sourceExpenseId 원본 지출 ID
     * @param from            조회 시작 일시 (포함)
     * @param to              조회 종료 일시 (포함)
     * @return 이미 등록되었으면 true
     */
    boolean existsBySourceExpenseIdAndExpenseDateBetween(Long sourceExpenseId, LocalDateTime from, LocalDateTime to);

    /**
     * 예정일이 경과한 계획 지출의 planned 플래그를 false로 일괄 갱신한다.
     *
     * @param now 현재 일시
     * @return 갱신된 행 수
     */
    @Modifying
    @Query("UPDATE Expense e SET e.planned = false WHERE e.planned = true AND e.expenseDate <= :now")
    int clearExpiredPlanned(LocalDateTime now);
}
