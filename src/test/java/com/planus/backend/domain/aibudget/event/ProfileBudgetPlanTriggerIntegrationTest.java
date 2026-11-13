package com.planus.backend.domain.aibudget.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.planus.backend.TestcontainersConfig;
import com.planus.backend.domain.aibudget.service.BudgetPlanService;
import com.planus.backend.domain.user.dto.FixedExpenseItem;
import com.planus.backend.domain.user.dto.UserProfileRequest;
import com.planus.backend.domain.user.dto.UserProfileResponse;
import com.planus.backend.domain.user.dto.UserProfileUpdateResponse;
import com.planus.backend.domain.user.entity.UserAccount;
import com.planus.backend.domain.user.repository.UserAccountRepository;
import com.planus.backend.domain.user.service.UserService;
import com.planus.backend.global.apiPayload.code.GeneralErrorCode;
import com.planus.backend.global.apiPayload.exception.GeneralException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 프로필 저장·수정 → 이벤트 → AFTER_COMMIT 리스너 → 예산 산출 트리거 경로를 실제 트랜잭션으로 검증한다.
 *
 * <p>{@link BudgetPlanService}만 모의 객체로 바꿔 AI 호출을 막고, 이벤트 게시자·트랜잭션·리스너는 실제 빈을 사용한다.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class ProfileBudgetPlanTriggerIntegrationTest {

    @MockitoBean
    private BudgetPlanService budgetPlanService;

    @Autowired
    private UserService userService;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private Long userId;

    @BeforeEach
    void setUp() {
        userId = userAccountRepository
                .save(UserAccount.builder()
                        .email("trigger-it@planus.com")
                        .password("encoded-password")
                        .nickname("트리거")
                        .build())
                .getId();
    }

    @AfterEach
    void tearDown() {
        userAccountRepository.deleteAll();
    }

    @Test
    @DisplayName("프로필 저장이 커밋된 뒤 예산 산출을 트리거하고, 트리거 시점에 저장된 프로필이 다른 연결에서도 보인다")
    void saveProfile_triggersAfterCommit() {
        AtomicReference<Long> incomeSeenByTrigger = new AtomicReference<>();
        doAnswer(inv -> {
                    incomeSeenByTrigger.set(readIncomeFromAnotherConnection(inv.getArgument(0)));
                    return null;
                })
                .when(budgetPlanService)
                .triggerBudgetPlan(anyLong());

        userService.saveProfile(userId, request(3_000_000L));

        verify(budgetPlanService).triggerBudgetPlan(userId);
        assertThat(incomeSeenByTrigger.get()).isEqualTo(3_000_000L);
    }

    @Test
    @DisplayName("프로필 수정이 커밋된 뒤 예산 산출을 트리거하고, 트리거 시점에 수정된 프로필이 다른 연결에서도 보인다")
    void updateProfile_triggersAfterCommitWithUpdatedProfile() {
        userService.saveProfile(userId, request(3_000_000L));

        AtomicReference<Long> incomeSeenByTrigger = new AtomicReference<>();
        doAnswer(inv -> {
                    incomeSeenByTrigger.set(readIncomeFromAnotherConnection(inv.getArgument(0)));
                    return null;
                })
                .when(budgetPlanService)
                .triggerBudgetPlan(anyLong());

        userService.updateProfile(userId, request(4_000_000L));

        assertThat(incomeSeenByTrigger.get()).isEqualTo(4_000_000L);
    }

    @Test
    @DisplayName("프로필 저장 트랜잭션이 롤백되면 예산 산출을 트리거하지 않는다")
    void saveProfile_rolledBack_doesNotTrigger() {
        transactionTemplate.executeWithoutResult(status -> {
            userService.saveProfile(userId, request(3_000_000L));
            status.setRollbackOnly();
        });

        verify(budgetPlanService, never()).triggerBudgetPlan(anyLong());
        assertThat(readIncomeFromAnotherConnection(userId)).isZero();
    }

    @Test
    @DisplayName("프로필 수정 트랜잭션이 롤백되면 예산 산출을 트리거하지 않는다")
    void updateProfile_rolledBack_doesNotTrigger() {
        transactionTemplate.executeWithoutResult(status -> {
            userService.updateProfile(userId, request(3_000_000L));
            status.setRollbackOnly();
        });

        verify(budgetPlanService, never()).triggerBudgetPlan(anyLong());
        assertThat(readIncomeFromAnotherConnection(userId)).isZero();
    }

    @Test
    @DisplayName("트리거가 BUDGET_PLAN_IN_PROGRESS로 실패해도 프로필 저장은 유지되고 성공 응답을 반환한다")
    void saveProfile_triggerInProgress_profileKeptAndSuccess() {
        doThrow(new GeneralException(GeneralErrorCode.BUDGET_PLAN_IN_PROGRESS))
                .when(budgetPlanService)
                .triggerBudgetPlan(anyLong());

        UserProfileResponse response = userService.saveProfile(userId, request(3_000_000L));

        assertThat(response.profileCompleted()).isTrue();
        assertThat(response.message()).isEqualTo("프로필 저장 완료. 예산 산출을 요청합니다.");
        assertThat(readIncomeFromAnotherConnection(userId)).isEqualTo(3_000_000L);
    }

    @Test
    @DisplayName("트리거가 예상하지 못한 예외로 실패해도 프로필 수정은 유지되고 성공 응답을 반환한다")
    void updateProfile_triggerUnexpectedError_profileKeptAndSuccess() {
        userService.saveProfile(userId, request(3_000_000L));
        doThrow(new IllegalStateException("db down")).when(budgetPlanService).triggerBudgetPlan(anyLong());

        UserProfileUpdateResponse response = userService.updateProfile(userId, request(4_000_000L));

        assertThat(response.message()).isEqualTo("프로필 수정 완료. 예산 재산출을 요청합니다.");
        assertThat(readIncomeFromAnotherConnection(userId)).isEqualTo(4_000_000L);
    }

    /** 현재 스레드에 묶인 트랜잭션 자원을 피하려고 다른 스레드(새 DB 연결)에서 커밋된 값을 읽는다. */
    private Long readIncomeFromAnotherConnection(Long id) {
        return CompletableFuture.supplyAsync(() ->
                        jdbcTemplate.queryForObject("SELECT monthly_income FROM \"user\" WHERE id = ?", Long.class, id))
                .join();
    }

    private UserProfileRequest request(long monthlyIncome) {
        return new UserProfileRequest(
                30, "MALE", monthlyIncome, List.of(new FixedExpenseItem("월세", 700_000L)), 500_000L, true, false, null);
    }
}
