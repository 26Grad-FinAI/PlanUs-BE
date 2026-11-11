package com.planus.backend.domain.expense.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.planus.backend.TestcontainersConfig;
import com.planus.backend.domain.aifeedback.entity.Expense;
import com.planus.backend.domain.aifeedback.repository.ExpenseRepository;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class ExpenseRepositoryLatestCopyTest {

    @Autowired
    private ExpenseRepository expenseRepository;

    @AfterEach
    void tearDown() {
        expenseRepository.deleteAll();
    }

    @Test
    @DisplayName("거래일이 앞당겨져도 id가 가장 큰 복사본을 반환한다")
    void findLatestCopy_returnsHighestId_regardlessOfExpenseDate() {
        // 원본 지출
        Expense source = expenseRepository.save(Expense.builder()
                .userId(1L)
                .type("EXPENSE")
                .amount(50000L)
                .title("월세")
                .expenseDate(LocalDateTime.of(2026, 8, 15, 3, 0))
                .categoryId(1)
                .recurring(true)
                .planned(false)
                .createdAt(LocalDateTime.of(2026, 8, 15, 3, 0))
                .build());

        // 첫 번째 복사본: 9월 15일, recurring=true
        Expense copy1 = expenseRepository.save(Expense.builder()
                .userId(1L)
                .type("EXPENSE")
                .amount(50000L)
                .title("월세")
                .expenseDate(LocalDateTime.of(2026, 9, 15, 3, 0))
                .categoryId(1)
                .recurring(true)
                .planned(false)
                .sourceExpenseId(source.getId())
                .createdAt(LocalDateTime.of(2026, 9, 15, 3, 0))
                .build());

        // 두 번째 복사본: recurring=false, 거래일을 8월 1일로 앞당김 (id는 copy1보다 큼)
        Expense copy2 = expenseRepository.save(Expense.builder()
                .userId(1L)
                .type("EXPENSE")
                .amount(50000L)
                .title("월세")
                .expenseDate(LocalDateTime.of(2026, 8, 1, 3, 0))
                .categoryId(1)
                .recurring(false)
                .planned(false)
                .sourceExpenseId(source.getId())
                .createdAt(LocalDateTime.of(2026, 10, 1, 3, 0))
                .build());

        Expense latest = expenseRepository.findLatestCopyBySourceExpenseId(source.getId());

        assertThat(latest).isNotNull();
        assertThat(latest.getId()).isEqualTo(copy2.getId());
        assertThat(latest.isRecurring()).isFalse();
    }

    @Test
    @DisplayName("복사본이 없으면 null을 반환한다")
    void findLatestCopy_noCopies_returnsNull() {
        Expense source = expenseRepository.save(Expense.builder()
                .userId(1L)
                .type("EXPENSE")
                .amount(50000L)
                .title("월세")
                .expenseDate(LocalDateTime.of(2026, 8, 15, 3, 0))
                .categoryId(1)
                .recurring(true)
                .planned(false)
                .createdAt(LocalDateTime.of(2026, 8, 15, 3, 0))
                .build());

        Expense latest = expenseRepository.findLatestCopyBySourceExpenseId(source.getId());

        assertThat(latest).isNull();
    }
}
