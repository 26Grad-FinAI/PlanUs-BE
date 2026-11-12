package com.planus.backend.domain.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.planus.backend.domain.aifeedback.repository.ExpenseRepository;
import com.planus.backend.domain.user.dto.FixedExpenseItem;
import com.planus.backend.domain.user.dto.SavingsStatusResponse;
import com.planus.backend.domain.user.dto.UserProfileGetResponse;
import com.planus.backend.domain.user.dto.UserProfileRequest;
import com.planus.backend.domain.user.dto.UserProfileResponse;
import com.planus.backend.domain.user.dto.UserProfileUpdateResponse;
import com.planus.backend.domain.user.entity.UserAccount;
import com.planus.backend.domain.user.repository.UserAccountRepository;
import com.planus.backend.global.apiPayload.code.GeneralErrorCode;
import com.planus.backend.global.apiPayload.exception.GeneralException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class UserServiceTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-11-15T00:00:00Z"), ZoneId.of("Asia/Seoul"));

    private UserAccountRepository userAccountRepository;
    private ExpenseRepository expenseRepository;
    private UserService userService;

    @BeforeEach
    void setUp() {
        userAccountRepository = mock(UserAccountRepository.class);
        expenseRepository = mock(ExpenseRepository.class);
        userService = new UserService(userAccountRepository, expenseRepository, FIXED_CLOCK);
    }

    @Nested
    @DisplayName("saveProfile 성공")
    class SaveProfileSuccess {

        @Test
        @DisplayName("프로필 저장 후 가용예산(소득 - 고정지출 - 저축목표)을 반환한다")
        void saveProfile_returnsCorrectAvailableBudget() {
            UserAccount user = spy(UserAccount.builder().id(1L).nickname("김스펜드").build());
            when(userAccountRepository.findById(1L)).thenReturn(Optional.of(user));

            UserProfileResponse response = userService.saveProfile(1L, validRequest());

            assertThat(response.availableBudget()).isEqualTo(1_500_000L);
        }

        @Test
        @DisplayName("거주지 없이 프로필을 저장해도 profileCompleted가 true이다")
        void saveProfile_profileCompletedIsTrue() {
            UserAccount user = spy(UserAccount.builder().id(1L).nickname("김스펜드").build());
            when(userAccountRepository.findById(1L)).thenReturn(Optional.of(user));

            UserProfileResponse response = userService.saveProfile(1L, validRequest());

            assertThat(response.profileCompleted()).isTrue();
        }

        @Test
        @DisplayName("프로필 저장 후 userId와 nickname이 올바르게 반환된다")
        void saveProfile_returnsUserIdAndNickname() {
            UserAccount user = spy(UserAccount.builder().id(1L).nickname("김스펜드").build());
            when(userAccountRepository.findById(1L)).thenReturn(Optional.of(user));

            UserProfileResponse response = userService.saveProfile(1L, validRequest());

            assertThat(response.userId()).isEqualTo(1L);
            assertThat(response.nickname()).isEqualTo("김스펜드");
        }

        @Test
        @DisplayName("saveProfile 호출 시 UserAccount.updateProfile이 요청값으로 실행된다")
        void saveProfile_invokesUpdateProfileWithRequestValues() {
            UserAccount user = spy(UserAccount.builder().id(1L).nickname("김스펜드").build());
            when(userAccountRepository.findById(1L)).thenReturn(Optional.of(user));

            userService.saveProfile(1L, validRequest());

            verify(user)
                    .updateProfile(
                            30,
                            "MALE",
                            3_000_000L,
                            List.of(new FixedExpenseItem("월세", 700_000L), new FixedExpenseItem("보험", 300_000L)),
                            500_000L,
                            true,
                            false,
                            null);
        }
    }

    @Nested
    @DisplayName("saveProfile 실패")
    class SaveProfileFailure {

        @Test
        @DisplayName("존재하지 않는 userId이면 NOT_FOUND 예외가 발생한다")
        void saveProfile_userNotFound_throwsNotFound() {
            when(userAccountRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> userService.saveProfile(999L, validRequest()))
                    .isInstanceOf(GeneralException.class)
                    .satisfies(ex ->
                            assertThat(((GeneralException) ex).getErrorCode()).isEqualTo(GeneralErrorCode.NOT_FOUND));
        }
    }

    @Nested
    @DisplayName("updateProfile 성공")
    class UpdateProfileSuccess {

        @Test
        @DisplayName("프로필 수정 후 변경된 가용예산을 반환한다")
        void updateProfile_returnsUpdatedAvailableBudget() {
            UserAccount user = spy(UserAccount.builder().id(1L).nickname("김스펜드").build());
            when(userAccountRepository.findById(1L)).thenReturn(Optional.of(user));

            UserProfileUpdateResponse response = userService.updateProfile(1L, validRequest());

            assertThat(response.availableBudget()).isEqualTo(1_500_000L);
        }

        @Test
        @DisplayName("프로필 수정 응답 메시지는 고정 문자열이다")
        void updateProfile_hasFixedMessage() {
            UserAccount user = spy(UserAccount.builder().id(1L).nickname("김스펜드").build());
            when(userAccountRepository.findById(1L)).thenReturn(Optional.of(user));

            UserProfileUpdateResponse response = userService.updateProfile(1L, validRequest());

            assertThat(response.message()).isEqualTo("프로필 수정 완료. 예산 재산출이 트리거됩니다.");
        }

        @Test
        @DisplayName("updateProfile 호출 시 UserAccount.updateProfile이 요청값으로 실행된다")
        void updateProfile_invokesUpdateProfileWithRequestValues() {
            UserAccount user = spy(UserAccount.builder().id(1L).nickname("김스펜드").build());
            when(userAccountRepository.findById(1L)).thenReturn(Optional.of(user));

            userService.updateProfile(1L, validRequest());

            verify(user)
                    .updateProfile(
                            30,
                            "MALE",
                            3_000_000L,
                            List.of(new FixedExpenseItem("월세", 700_000L), new FixedExpenseItem("보험", 300_000L)),
                            500_000L,
                            true,
                            false,
                            null);
        }
    }

    @Nested
    @DisplayName("updateProfile 실패")
    class UpdateProfileFailure {

        @Test
        @DisplayName("존재하지 않는 userId이면 NOT_FOUND 예외가 발생한다")
        void updateProfile_userNotFound_throwsNotFound() {
            when(userAccountRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> userService.updateProfile(999L, validRequest()))
                    .isInstanceOf(GeneralException.class)
                    .satisfies(ex ->
                            assertThat(((GeneralException) ex).getErrorCode()).isEqualTo(GeneralErrorCode.NOT_FOUND));
        }
    }

    @Nested
    @DisplayName("getProfile 성공")
    class GetProfileSuccess {

        @Test
        @DisplayName("userId로 조회 시 프로필 전체 정보를 반환한다")
        void getProfile_returnsFullProfile() {
            UserAccount user = UserAccount.builder()
                    .id(1L)
                    .email("spendwise@email.com")
                    .nickname("김스펜드")
                    .age(28)
                    .gender("MALE")
                    .monthlyIncome(3_000_000L)
                    .monthlyFixedExpenses(
                            List.of(new FixedExpenseItem("월세", 700_000L), new FixedExpenseItem("보험", 300_000L)))
                    .monthlySavingsGoal(500_000L)
                    .employmentStatus(true)
                    .homeOwnership(false)
                    .build();
            when(userAccountRepository.findById(1L)).thenReturn(Optional.of(user));

            UserProfileGetResponse response = userService.getProfile(1L);

            assertThat(response.userId()).isEqualTo(1L);
            assertThat(response.email()).isEqualTo("spendwise@email.com");
            assertThat(response.totalFixedExpenses()).isEqualTo(1_000_000L);
            assertThat(response.availableBudget()).isEqualTo(1_500_000L);
            assertThat(response.profileCompleted()).isTrue();
        }
    }

    @Nested
    @DisplayName("getProfile 실패")
    class GetProfileFailure {

        @Test
        @DisplayName("존재하지 않는 userId이면 NOT_FOUND 예외가 발생한다")
        void getProfile_userNotFound_throwsNotFound() {
            when(userAccountRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> userService.getProfile(999L))
                    .isInstanceOf(GeneralException.class)
                    .satisfies(ex ->
                            assertThat(((GeneralException) ex).getErrorCode()).isEqualTo(GeneralErrorCode.NOT_FOUND));
        }
    }

    @Nested
    @DisplayName("getSavingsStatus 성공")
    class GetSavingsStatusSuccess {

        @Test
        @DisplayName("회원가입 월부터 현재까지 월별 저축 기록과 총 저축 금액을 반환한다")
        void getSavingsStatus_returnsMonthlyRecordsAndTotal() {
            // 2026-09에 가입, 현재 2026-11 → 3개월 기록
            UserAccount user = UserAccount.builder()
                    .id(1L)
                    .monthlyIncome(3_000_000L)
                    .monthlyFixedExpenses(
                            List.of(new FixedExpenseItem("월세", 700_000L), new FixedExpenseItem("보험", 300_000L)))
                    .monthlySavingsGoal(500_000L)
                    .createdAt(LocalDateTime.of(2026, 9, 15, 10, 0))
                    .build();
            when(userAccountRepository.findById(1L)).thenReturn(Optional.of(user));

            // 9월: 변동지출 800,000 / 추가수입 0
            // 10월: 변동지출 1,500,000 / 추가수입 100,000
            // 11월: 변동지출 500,000 / 추가수입 200,000
            when(expenseRepository.sumExpensesGroupByMonth(eq(1L), any(), any()))
                    .thenReturn(List.of(
                            new Object[] {2026, 9, 800_000L},
                            new Object[] {2026, 10, 1_500_000L},
                            new Object[] {2026, 11, 500_000L}));
            when(expenseRepository.sumIncomeGroupByMonth(eq(1L), any(), any()))
                    .thenReturn(List.of(new Object[] {2026, 10, 100_000L}, new Object[] {2026, 11, 200_000L}));

            SavingsStatusResponse response = userService.getSavingsStatus(1L);

            // 고정지출 = 700,000 + 300,000 = 1,000,000
            // 9월: (3,000,000 + 0) - (1,000,000 + 800,000) = +1,200,000
            // 10월: (3,000,000 + 100,000) - (1,000,000 + 1,500,000) = +600,000
            // 11월: (3,000,000 + 200,000) - (1,000,000 + 500,000) = +1,700,000
            assertThat(response.monthlyRecords()).hasSize(3);
            assertThat(response.monthlyRecords().get(0).yearMonth()).isEqualTo("2026-09");
            assertThat(response.monthlyRecords().get(0).savings()).isEqualTo(1_200_000L);
            assertThat(response.monthlyRecords().get(1).yearMonth()).isEqualTo("2026-10");
            assertThat(response.monthlyRecords().get(1).savings()).isEqualTo(600_000L);
            assertThat(response.monthlyRecords().get(2).yearMonth()).isEqualTo("2026-11");
            assertThat(response.monthlyRecords().get(2).savings()).isEqualTo(1_700_000L);
            assertThat(response.totalSavings()).isEqualTo(3_500_000L);
        }

        @Test
        @DisplayName("지출이 수입보다 많은 달은 음수 저축액을 반환한다")
        void getSavingsStatus_overspentMonth_returnsNegativeSavings() {
            UserAccount user = UserAccount.builder()
                    .id(1L)
                    .monthlyIncome(2_000_000L)
                    .monthlyFixedExpenses(List.of(new FixedExpenseItem("월세", 500_000L)))
                    .monthlySavingsGoal(300_000L)
                    .createdAt(LocalDateTime.of(2026, 10, 1, 0, 0))
                    .build();
            when(userAccountRepository.findById(1L)).thenReturn(Optional.of(user));

            // 10월: 변동지출 2,000,000 (초과지출)
            // 11월: 변동지출 500,000 (저축 성공)
            when(expenseRepository.sumExpensesGroupByMonth(eq(1L), any(), any()))
                    .thenReturn(List.of(new Object[] {2026, 10, 2_000_000L}, new Object[] {2026, 11, 500_000L}));
            when(expenseRepository.sumIncomeGroupByMonth(eq(1L), any(), any())).thenReturn(List.of());

            SavingsStatusResponse response = userService.getSavingsStatus(1L);

            // 고정지출 = 500,000
            // 10월: (2,000,000 + 0) - (500,000 + 2,000,000) = -500,000
            // 11월: (2,000,000 + 0) - (500,000 + 500,000) = +1,000,000
            assertThat(response.monthlyRecords().get(0).savings()).isEqualTo(-500_000L);
            assertThat(response.monthlyRecords().get(1).savings()).isEqualTo(1_000_000L);
            assertThat(response.totalSavings()).isEqualTo(500_000L);
        }

        @Test
        @DisplayName("거래 내역이 없으면 프로필 기준 저축액만 반환한다")
        void getSavingsStatus_noTransactions_returnsProfileBasedSavings() {
            UserAccount user = UserAccount.builder()
                    .id(1L)
                    .monthlyIncome(3_000_000L)
                    .monthlyFixedExpenses(List.of(new FixedExpenseItem("월세", 700_000L)))
                    .monthlySavingsGoal(500_000L)
                    .createdAt(LocalDateTime.of(2026, 11, 1, 0, 0))
                    .build();
            when(userAccountRepository.findById(1L)).thenReturn(Optional.of(user));
            when(expenseRepository.sumExpensesGroupByMonth(eq(1L), any(), any()))
                    .thenReturn(List.of());
            when(expenseRepository.sumIncomeGroupByMonth(eq(1L), any(), any())).thenReturn(List.of());

            SavingsStatusResponse response = userService.getSavingsStatus(1L);

            // 11월: (3,000,000 + 0) - (700,000 + 0) = +2,300,000
            assertThat(response.monthlyRecords()).hasSize(1);
            assertThat(response.monthlyRecords().get(0).savings()).isEqualTo(2_300_000L);
            assertThat(response.totalSavings()).isEqualTo(2_300_000L);
        }
    }

    @Nested
    @DisplayName("getSavingsStatus 실패")
    class GetSavingsStatusFailure {

        @Test
        @DisplayName("존재하지 않는 userId이면 NOT_FOUND 예외가 발생한다")
        void getSavingsStatus_userNotFound_throwsNotFound() {
            when(userAccountRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> userService.getSavingsStatus(999L))
                    .isInstanceOf(GeneralException.class)
                    .satisfies(ex ->
                            assertThat(((GeneralException) ex).getErrorCode()).isEqualTo(GeneralErrorCode.NOT_FOUND));
        }
    }

    private UserProfileRequest validRequest() {
        return new UserProfileRequest(
                30,
                "MALE",
                3_000_000L,
                List.of(new FixedExpenseItem("월세", 700_000L), new FixedExpenseItem("보험", 300_000L)),
                500_000L,
                true,
                false,
                null);
    }
}
