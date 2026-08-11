package com.planus.backend.domain.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.planus.backend.domain.user.dto.UserProfileGetResponse;
import com.planus.backend.domain.user.dto.UserProfileRequest;
import com.planus.backend.domain.user.dto.UserProfileResponse;
import com.planus.backend.domain.user.dto.UserProfileUpdateResponse;
import com.planus.backend.domain.user.entity.UserAccount;
import com.planus.backend.domain.user.repository.UserAccountRepository;
import com.planus.backend.global.apiPayload.code.GeneralErrorCode;
import com.planus.backend.global.apiPayload.exception.GeneralException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class UserServiceTest {

    private UserAccountRepository userAccountRepository;
    private UserService userService;

    @BeforeEach
    void setUp() {
        userAccountRepository = mock(UserAccountRepository.class);
        userService = new UserService(userAccountRepository);
    }

    @Nested
    @DisplayName("saveProfile 성공")
    class SaveProfileSuccess {

        @Test
        @DisplayName("프로필 저장 후 가용예산(소득 - 고정지출 - 저축목표)을 반환한다")
        void saveProfile_returnsCorrectAvailableBudget() {
            UserAccount user = spy(UserAccount.builder()
                    .id(1L)
                    .nickname("김스펜드")
                    .build());
            when(userAccountRepository.findById(1L)).thenReturn(Optional.of(user));

            UserProfileResponse response = userService.saveProfile(1L, validRequest());

            assertThat(response.availableBudget()).isEqualTo(1_500_000L);
        }

        @Test
        @DisplayName("프로필 저장 후 profileCompleted가 true이다")
        void saveProfile_profileCompletedIsTrue() {
            UserAccount user = spy(UserAccount.builder()
                    .id(1L)
                    .nickname("김스펜드")
                    .build());
            when(userAccountRepository.findById(1L)).thenReturn(Optional.of(user));

            UserProfileResponse response = userService.saveProfile(1L, validRequest());

            assertThat(response.profileCompleted()).isTrue();
        }

        @Test
        @DisplayName("프로필 저장 후 userId와 nickname이 올바르게 반환된다")
        void saveProfile_returnsUserIdAndNickname() {
            UserAccount user = spy(UserAccount.builder()
                    .id(1L)
                    .nickname("김스펜드")
                    .build());
            when(userAccountRepository.findById(1L)).thenReturn(Optional.of(user));

            UserProfileResponse response = userService.saveProfile(1L, validRequest());

            assertThat(response.userId()).isEqualTo(1L);
            assertThat(response.nickname()).isEqualTo("김스펜드");
        }

        @Test
        @DisplayName("saveProfile 호출 시 UserAccount.updateProfile이 요청값으로 실행된다")
        void saveProfile_invokesUpdateProfileWithRequestValues() {
            UserAccount user = spy(UserAccount.builder()
                    .id(1L)
                    .nickname("김스펜드")
                    .build());
            when(userAccountRepository.findById(1L)).thenReturn(Optional.of(user));

            userService.saveProfile(1L, validRequest());

            verify(user).updateProfile(
                    30,
                    "MALE",
                    "서울",
                    3_000_000L,
                    1_000_000L,
                    500_000L,
                    List.of("DINING", "TRAVEL"),
                    "SAVING",
                    true,
                    false);
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
                    .satisfies(ex -> assertThat(((GeneralException) ex).getErrorCode())
                            .isEqualTo(GeneralErrorCode.NOT_FOUND));
        }
    }

    @Nested
    @DisplayName("updateProfile 성공")
    class UpdateProfileSuccess {

        @Test
        @DisplayName("프로필 수정 후 변경된 가용예산을 반환한다")
        void updateProfile_returnsUpdatedAvailableBudget() {
            UserAccount user = spy(UserAccount.builder()
                    .id(1L)
                    .nickname("김스펜드")
                    .build());
            when(userAccountRepository.findById(1L)).thenReturn(Optional.of(user));

            UserProfileUpdateResponse response = userService.updateProfile(1L, validRequest());

            assertThat(response.availableBudget()).isEqualTo(1_500_000L);
        }

        @Test
        @DisplayName("프로필 수정 응답 메시지는 고정 문자열이다")
        void updateProfile_hasFixedMessage() {
            UserAccount user = spy(UserAccount.builder()
                    .id(1L)
                    .nickname("김스펜드")
                    .build());
            when(userAccountRepository.findById(1L)).thenReturn(Optional.of(user));

            UserProfileUpdateResponse response = userService.updateProfile(1L, validRequest());

            assertThat(response.message()).isEqualTo("프로필 수정 완료. 예산 재산출이 트리거됩니다.");
        }

        @Test
        @DisplayName("updateProfile 호출 시 UserAccount.updateProfile이 요청값으로 실행된다")
        void updateProfile_invokesUpdateProfileWithRequestValues() {
            UserAccount user = spy(UserAccount.builder()
                    .id(1L)
                    .nickname("김스펜드")
                    .build());
            when(userAccountRepository.findById(1L)).thenReturn(Optional.of(user));

            userService.updateProfile(1L, validRequest());

            verify(user).updateProfile(
                    30, "MALE", "서울",
                    3_000_000L, 1_000_000L, 500_000L,
                    List.of("DINING", "TRAVEL"),
                    "SAVING", true, false);
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
                    .satisfies(ex -> assertThat(((GeneralException) ex).getErrorCode())
                            .isEqualTo(GeneralErrorCode.NOT_FOUND));
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
                    .residence("서울특별시")
                    .monthlyIncome(3_000_000L)
                    .monthlyFixedExpenses(1_000_000L)
                    .monthlySavingsGoal(500_000L)
                    .hobbies(List.of("DINING", "TRAVEL"))
                    .spendingHabit("BALANCED")
                    .employmentStatus(true)
                    .homeOwnership(false)
                    .build();
            when(userAccountRepository.findById(1L)).thenReturn(Optional.of(user));

            UserProfileGetResponse response = userService.getProfile(1L);

            assertThat(response.userId()).isEqualTo(1L);
            assertThat(response.email()).isEqualTo("spendwise@email.com");
            assertThat(response.hobbies()).containsExactly("DINING", "TRAVEL");
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
                    .satisfies(ex -> assertThat(((GeneralException) ex).getErrorCode())
                            .isEqualTo(GeneralErrorCode.NOT_FOUND));
        }
    }

    private UserProfileRequest validRequest() {
        return new UserProfileRequest(
                30,
                "MALE",
                "서울",
                3_000_000L,
                1_000_000L,
                500_000L,
                List.of("DINING", "TRAVEL"),
                "SAVING",
                true,
                false);
    }
}
