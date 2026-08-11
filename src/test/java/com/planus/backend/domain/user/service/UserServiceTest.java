package com.planus.backend.domain.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.planus.backend.domain.user.dto.UserProfileRequest;
import com.planus.backend.domain.user.dto.UserProfileResponse;
import com.planus.backend.domain.user.entity.UserAccount;
import com.planus.backend.domain.user.repository.UserAccountRepository;
import com.planus.backend.global.apiPayload.code.GeneralErrorCode;
import com.planus.backend.global.apiPayload.exception.GeneralException;
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
    class Success {

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
                    "독서",
                    "SAVING",
                    true,
                    false);
        }
    }

    @Nested
    @DisplayName("saveProfile 실패")
    class Failure {

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

    private UserProfileRequest validRequest() {
        return new UserProfileRequest(
                30,
                "MALE",
                "서울",
                3_000_000L,
                1_000_000L,
                500_000L,
                "독서",
                "SAVING",
                true,
                false);
    }
}
