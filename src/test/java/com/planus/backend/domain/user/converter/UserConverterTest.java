package com.planus.backend.domain.user.converter;

import static org.assertj.core.api.Assertions.assertThat;

import com.planus.backend.domain.user.dto.FixedExpenseItem;
import com.planus.backend.domain.user.dto.UserProfileGetResponse;
import com.planus.backend.domain.user.dto.UserProfileResponse;
import com.planus.backend.domain.user.entity.UserAccount;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class UserConverterTest {

    private static final List<FixedExpenseItem> FIXED_EXPENSES_1M =
            List.of(new FixedExpenseItem("월세", 700_000L), new FixedExpenseItem("보험", 300_000L));

    private static final List<FixedExpenseItem> FIXED_EXPENSES_500K = List.of(new FixedExpenseItem("월세", 500_000L));

    @Nested
    @DisplayName("toProfileResponse (프로필 저장 응답)")
    class ToProfileResponse {

        @Test
        @DisplayName("userId, nickname, availableBudget이 UserAccount 값으로 올바르게 매핑된다")
        void mapsFieldsCorrectly() {
            UserAccount user = UserAccount.builder()
                    .id(1L)
                    .nickname("김스펜드")
                    .monthlyIncome(3_000_000L)
                    .monthlyFixedExpenses(FIXED_EXPENSES_1M)
                    .monthlySavingsGoal(500_000L)
                    .build();

            UserProfileResponse response = UserConverter.toProfileResponse(user);

            assertThat(response.userId()).isEqualTo(1L);
            assertThat(response.nickname()).isEqualTo("김스펜드");
            assertThat(response.availableBudget()).isEqualTo(1_500_000L);
        }

        @Test
        @DisplayName("message는 고정 문자열 '프로필 저장 완료.'이다")
        void hasFixedMessage() {
            UserAccount user = UserAccount.builder()
                    .id(1L)
                    .nickname("테스트")
                    .monthlyIncome(2_000_000L)
                    .monthlyFixedExpenses(FIXED_EXPENSES_500K)
                    .monthlySavingsGoal(500_000L)
                    .build();

            UserProfileResponse response = UserConverter.toProfileResponse(user);

            assertThat(response.message()).isEqualTo("프로필 저장 완료.");
        }

        @Test
        @DisplayName("필수 필드가 모두 채워진 유저는 profileCompleted가 true이다")
        void profileCompleted_true() {
            UserAccount user = UserAccount.builder()
                    .id(1L)
                    .nickname("김스펜드")
                    .age(30)
                    .gender("MALE")
                    .residence("서울")
                    .monthlyIncome(3_000_000L)
                    .monthlyFixedExpenses(FIXED_EXPENSES_1M)
                    .monthlySavingsGoal(500_000L)
                    .employmentStatus(true)
                    .homeOwnership(false)
                    .build();

            UserProfileResponse response = UserConverter.toProfileResponse(user);

            assertThat(response.profileCompleted()).isTrue();
        }

        @Test
        @DisplayName("age와 monthlyIncome만 있고 나머지 필드가 없으면 profileCompleted가 false이다")
        void profileCompleted_false_whenPartialFields() {
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

    @Nested
    @DisplayName("toProfileGetResponse (프로필 조회 응답)")
    class ToProfileGetResponse {

        @Test
        @DisplayName("모든 프로필 필드가 UserAccount 값으로 올바르게 매핑된다")
        void mapsAllFieldsCorrectly() {
            UserAccount user = UserAccount.builder()
                    .id(1L)
                    .email("spendwise@email.com")
                    .nickname("김스펜드")
                    .age(28)
                    .gender("MALE")
                    .residence("서울특별시")
                    .monthlyIncome(3_000_000L)
                    .monthlyFixedExpenses(FIXED_EXPENSES_1M)
                    .monthlySavingsGoal(500_000L)
                    .employmentStatus(true)
                    .homeOwnership(false)
                    .build();

            UserProfileGetResponse response = UserConverter.toProfileGetResponse(user);

            assertThat(response.userId()).isEqualTo(1L);
            assertThat(response.email()).isEqualTo("spendwise@email.com");
            assertThat(response.nickname()).isEqualTo("김스펜드");
            assertThat(response.age()).isEqualTo(28);
            assertThat(response.gender()).isEqualTo("MALE");
            assertThat(response.residence()).isEqualTo("서울특별시");
            assertThat(response.monthlyIncome()).isEqualTo(3_000_000L);
            assertThat(response.totalFixedExpenses()).isEqualTo(1_000_000L);
            assertThat(response.availableBudget()).isEqualTo(1_500_000L);
            assertThat(response.employmentStatus()).isTrue();
            assertThat(response.homeOwnership()).isFalse();
        }

        @Test
        @DisplayName("프로필이 완성된 유저는 profileCompleted가 true이다")
        void profileCompleted_true() {
            UserAccount user = UserAccount.builder()
                    .id(1L)
                    .nickname("김스펜드")
                    .age(28)
                    .gender("MALE")
                    .residence("서울특별시")
                    .monthlyIncome(3_000_000L)
                    .monthlyFixedExpenses(FIXED_EXPENSES_1M)
                    .monthlySavingsGoal(500_000L)
                    .employmentStatus(true)
                    .homeOwnership(false)
                    .build();

            UserProfileGetResponse response = UserConverter.toProfileGetResponse(user);

            assertThat(response.profileCompleted()).isTrue();
        }
    }
}
