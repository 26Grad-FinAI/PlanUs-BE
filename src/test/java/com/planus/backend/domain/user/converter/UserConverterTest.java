package com.planus.backend.domain.user.converter;

import static org.assertj.core.api.Assertions.assertThat;

import com.planus.backend.domain.user.dto.UserProfileResponse;
import com.planus.backend.domain.user.entity.UserAccount;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UserConverterTest {

    @Test
    @DisplayName("userId, nickname, availableBudget이 UserAccount 값으로 올바르게 매핑된다")
    void toProfileResponse_mapsFieldsCorrectly() {
        UserAccount user = UserAccount.builder()
                .id(1L)
                .nickname("김스펜드")
                .monthlyIncome(3_000_000L)
                .monthlyFixedExpenses(1_000_000L)
                .monthlySavingsGoal(500_000L)
                .build();

        UserProfileResponse response = UserConverter.toProfileResponse(user);

        assertThat(response.userId()).isEqualTo(1L);
        assertThat(response.nickname()).isEqualTo("김스펜드");
        assertThat(response.availableBudget()).isEqualTo(1_500_000L);
    }

    @Test
    @DisplayName("message는 고정 문자열 '프로필 저장 완료. AI 예산 산출이 시작됩니다.'이다")
    void toProfileResponse_hasFixedMessage() {
        UserAccount user = UserAccount.builder()
                .id(1L)
                .nickname("테스트")
                .monthlyIncome(2_000_000L)
                .monthlyFixedExpenses(500_000L)
                .monthlySavingsGoal(500_000L)
                .build();

        UserProfileResponse response = UserConverter.toProfileResponse(user);

        assertThat(response.message()).isEqualTo("프로필 저장 완료. AI 예산 산출이 시작됩니다.");
    }

    @Test
    @DisplayName("10개 필드가 모두 채워진 유저는 profileCompleted가 true이다")
    void toProfileResponse_profileCompleted_true() {
        UserAccount user = UserAccount.builder()
                .id(1L)
                .nickname("김스펜드")
                .age(30)
                .gender("MALE")
                .residence("서울")
                .monthlyIncome(3_000_000L)
                .monthlyFixedExpenses(1_000_000L)
                .monthlySavingsGoal(500_000L)
                .hobby("독서")
                .spendingHabit("SAVING")
                .employmentStatus(true)
                .homeOwnership(false)
                .build();

        UserProfileResponse response = UserConverter.toProfileResponse(user);

        assertThat(response.profileCompleted()).isTrue();
    }

    @Test
    @DisplayName("age와 monthlyIncome만 있고 나머지 필드가 없으면 profileCompleted가 false이다")
    void toProfileResponse_profileCompleted_false_whenPartialFields() {
        UserAccount user = UserAccount.builder()
                .id(1L)
                .nickname("김스펜드")
                .age(30)
                .monthlyIncome(3_000_000L)
                .build();

        UserProfileResponse response = UserConverter.toProfileResponse(user);

        assertThat(response.profileCompleted()).isFalse();
    }
}
