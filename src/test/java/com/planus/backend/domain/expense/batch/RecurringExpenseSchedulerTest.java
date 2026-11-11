package com.planus.backend.domain.expense.batch;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.planus.backend.domain.aifeedback.entity.Expense;
import com.planus.backend.domain.aifeedback.repository.ExpenseRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class RecurringExpenseSchedulerTest {

    private ExpenseRepository expenseRepository;
    private RecurringExpenseItemProcessor itemProcessor;
    private RecurringExpenseScheduler scheduler;

    /** 2026-10-15 03:00 KST 고정 시계 */
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);

    private static final Clock FIXED_CLOCK =
            Clock.fixed(TODAY.atTime(3, 0).atZone(ZoneId.of("Asia/Seoul")).toInstant(), ZoneId.of("Asia/Seoul"));

    @BeforeEach
    void setUp() {
        expenseRepository = mock(ExpenseRepository.class);
        itemProcessor = mock(RecurringExpenseItemProcessor.class);
        scheduler = new RecurringExpenseScheduler(expenseRepository, itemProcessor, FIXED_CLOCK);

        when(itemProcessor.clearExpiredPlanned(any())).thenReturn(0);
    }

    private Expense sourceExpense(Long id, int day) {
        return Expense.builder()
                .id(id)
                .userId(1L)
                .type("EXPENSE")
                .amount(50000L)
                .title("월세")
                .expenseDate(LocalDateTime.of(2026, 9, day, 3, 0))
                .categoryId(1)
                .recurring(true)
                .planned(false)
                .createdAt(LocalDateTime.of(2026, 9, day, 3, 0))
                .build();
    }

    private Expense copyExpense(Long id, Long sourceId, boolean recurring, LocalDateTime expenseDate) {
        return Expense.builder()
                .id(id)
                .userId(1L)
                .type("EXPENSE")
                .amount(50000L)
                .title("월세")
                .expenseDate(expenseDate)
                .categoryId(1)
                .recurring(recurring)
                .planned(false)
                .sourceExpenseId(sourceId)
                .createdAt(expenseDate)
                .build();
    }

    @Nested
    @DisplayName("최신 복사본 기준 반복 여부 판단")
    class LatestCopyRecurringCheck {

        @Test
        @DisplayName("복사본이 없는 원본은 정상적으로 자동 등록된다")
        void noCopy_createsExpense() {
            Expense source = sourceExpense(1L, 15);
            when(expenseRepository.findByRecurringAndTypeAndSourceExpenseIdIsNull(true, "EXPENSE"))
                    .thenReturn(List.of(source));
            when(expenseRepository.findLatestCopyBySourceExpenseId(1L)).thenReturn(null);
            when(itemProcessor.process(source, TODAY)).thenReturn(true);

            scheduler.executeRecurringExpenses();

            verify(itemProcessor).process(source, TODAY);
        }

        @Test
        @DisplayName("최신 복사본의 recurring이 false이면 자동 등록을 스킵한다")
        void latestCopyNotRecurring_skips() {
            Expense source = sourceExpense(1L, 15);
            Expense copy = copyExpense(10L, 1L, false, LocalDateTime.of(2026, 9, 15, 3, 0));

            when(expenseRepository.findByRecurringAndTypeAndSourceExpenseIdIsNull(true, "EXPENSE"))
                    .thenReturn(List.of(source));
            when(expenseRepository.findLatestCopyBySourceExpenseId(1L)).thenReturn(copy);

            scheduler.executeRecurringExpenses();

            verify(itemProcessor, never()).process(any(Expense.class), any(LocalDate.class));
        }

        @Test
        @DisplayName("최신 복사본의 recurring이 true이면 정상적으로 자동 등록된다")
        void latestCopyRecurring_createsExpense() {
            Expense source = sourceExpense(1L, 15);
            Expense copy = copyExpense(10L, 1L, true, LocalDateTime.of(2026, 9, 15, 3, 0));

            when(expenseRepository.findByRecurringAndTypeAndSourceExpenseIdIsNull(true, "EXPENSE"))
                    .thenReturn(List.of(source));
            when(expenseRepository.findLatestCopyBySourceExpenseId(1L)).thenReturn(copy);
            when(itemProcessor.process(source, TODAY)).thenReturn(true);

            scheduler.executeRecurringExpenses();

            verify(itemProcessor).process(source, TODAY);
        }

        @Test
        @DisplayName("최신 복사본이 거래일을 앞당겨도 id가 가장 큰 복사본 기준으로 판단한다")
        void latestCopyWithEarlierDate_usesIdOrder() {
            Expense source = sourceExpense(1L, 15);
            // id=10: recurring=true, 9월 15일
            // id=20: recurring=false, 거래일을 8월 1일로 앞당김 (id가 더 크므로 최신)
            Expense latestById = copyExpense(20L, 1L, false, LocalDateTime.of(2026, 8, 1, 3, 0));

            when(expenseRepository.findByRecurringAndTypeAndSourceExpenseIdIsNull(true, "EXPENSE"))
                    .thenReturn(List.of(source));
            when(expenseRepository.findLatestCopyBySourceExpenseId(1L)).thenReturn(latestById);

            scheduler.executeRecurringExpenses();

            verify(itemProcessor, never()).process(any(Expense.class), any(LocalDate.class));
        }
    }

    @Nested
    @DisplayName("복사본 조회 실패 격리")
    class CopyLookupFailureIsolation {

        @Test
        @DisplayName("복사본 조회에서 예외가 발생해도 다음 원본을 계속 처리한다")
        void copyLookupException_continuesNextSource() {
            Expense source1 = sourceExpense(1L, 15);
            Expense source2 = sourceExpense(2L, 15);

            when(expenseRepository.findByRecurringAndTypeAndSourceExpenseIdIsNull(true, "EXPENSE"))
                    .thenReturn(List.of(source1, source2));
            when(expenseRepository.findLatestCopyBySourceExpenseId(1L)).thenThrow(new RuntimeException("DB 오류"));
            when(expenseRepository.findLatestCopyBySourceExpenseId(2L)).thenReturn(null);
            when(itemProcessor.process(source2, TODAY)).thenReturn(true);

            scheduler.executeRecurringExpenses();

            verify(itemProcessor, never()).process(source1, TODAY);
            verify(itemProcessor).process(source2, TODAY);
        }
    }

    @Nested
    @DisplayName("기존 스케줄러 동작")
    class ExistingBehavior {

        @Test
        @DisplayName("원본 지출일이 오늘 이후이면 스킵한다")
        void futureSource_skips() {
            Expense todaySource = Expense.builder()
                    .id(2L)
                    .userId(1L)
                    .type("EXPENSE")
                    .amount(50000L)
                    .title("월세")
                    .expenseDate(LocalDateTime.of(2026, 10, 15, 3, 0))
                    .categoryId(1)
                    .recurring(true)
                    .planned(false)
                    .createdAt(LocalDateTime.of(2026, 10, 15, 3, 0))
                    .build();

            when(expenseRepository.findByRecurringAndTypeAndSourceExpenseIdIsNull(true, "EXPENSE"))
                    .thenReturn(List.of(todaySource));

            scheduler.executeRecurringExpenses();

            verify(itemProcessor, never()).process(any(Expense.class), any(LocalDate.class));
        }

        @Test
        @DisplayName("원본 지출일이 오늘과 다른 날이면 스킵한다")
        void differentDay_skips() {
            Expense source = sourceExpense(3L, 20);

            when(expenseRepository.findByRecurringAndTypeAndSourceExpenseIdIsNull(true, "EXPENSE"))
                    .thenReturn(List.of(source));

            scheduler.executeRecurringExpenses();

            verify(itemProcessor, never()).process(any(Expense.class), any(LocalDate.class));
        }
    }
}
