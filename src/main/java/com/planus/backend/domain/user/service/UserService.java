package com.planus.backend.domain.user.service;

import com.planus.backend.domain.aifeedback.repository.ExpenseRepository;
import com.planus.backend.domain.user.converter.UserConverter;
import com.planus.backend.domain.user.dto.SavingsStatusResponse;
import com.planus.backend.domain.user.dto.SavingsStatusResponse.MonthlyRecord;
import com.planus.backend.domain.user.dto.UserProfileGetResponse;
import com.planus.backend.domain.user.dto.UserProfileRequest;
import com.planus.backend.domain.user.dto.UserProfileResponse;
import com.planus.backend.domain.user.dto.UserProfileUpdateResponse;
import com.planus.backend.domain.user.entity.UserAccount;
import com.planus.backend.domain.user.event.ProfileSavedEvent;
import com.planus.backend.domain.user.repository.UserAccountRepository;
import com.planus.backend.global.apiPayload.code.GeneralErrorCode;
import com.planus.backend.global.apiPayload.exception.GeneralException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 사용자 프로필 비즈니스 로직. */
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserAccountRepository userAccountRepository;
    private final ExpenseRepository expenseRepository;
    private final Clock clock;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 사용자 프로필을 저장하고 가용예산을 반환한다. 커밋 후 AI 예산 산출을 자동으로 요청한다(시작은 보장하지 않음).
     *
     * @param userId  JWT 필터가 주입한 사용자 ID
     * @param request 프로필 요청 DTO
     * @return 저장된 사용자 정보 및 가용예산
     */
    @Transactional
    public UserProfileResponse saveProfile(Long userId, UserProfileRequest request) {
        UserAccount user = userAccountRepository
                .findById(userId)
                .orElseThrow(() -> new GeneralException(GeneralErrorCode.NOT_FOUND));

        user.updateProfile(
                request.age(),
                request.gender(),
                request.monthlyIncome(),
                request.monthlyFixedExpenses(),
                request.monthlySavingsGoal(),
                request.employmentStatus(),
                request.homeOwnership(),
                request.selfIntroduction());
        // 커밋 후 예산 산출 자동 트리거 (ProfileBudgetPlanTrigger)
        eventPublisher.publishEvent(new ProfileSavedEvent(userId));

        return UserConverter.toProfileResponse(user);
    }

    /**
     * 사용자 프로필을 수정한다. 커밋 후 AI 예산 재산출을 자동으로 요청한다(시작은 보장하지 않음).
     *
     * @param userId  JWT 필터가 주입한 사용자 ID
     * @param request 수정할 프로필 요청 DTO
     * @return 수정된 가용예산 및 수정일시
     */
    @Transactional
    public UserProfileUpdateResponse updateProfile(Long userId, UserProfileRequest request) {
        UserAccount user = userAccountRepository
                .findById(userId)
                .orElseThrow(() -> new GeneralException(GeneralErrorCode.NOT_FOUND));

        user.updateProfile(
                request.age(),
                request.gender(),
                request.monthlyIncome(),
                request.monthlyFixedExpenses(),
                request.monthlySavingsGoal(),
                request.employmentStatus(),
                request.homeOwnership(),
                request.selfIntroduction());
        // 커밋 후 예산 산출 자동 트리거 (ProfileBudgetPlanTrigger)
        eventPublisher.publishEvent(new ProfileSavedEvent(userId));

        return UserConverter.toProfileUpdateResponse(user);
    }

    /**
     * FCM 디바이스 토큰을 갱신한다.
     *
     * @param userId   JWT 필터가 주입한 사용자 ID
     * @param fcmToken FCM 디바이스 토큰
     */
    @Transactional
    public void updateFcmToken(Long userId, String fcmToken) {
        UserAccount user = userAccountRepository
                .findById(userId)
                .orElseThrow(() -> new GeneralException(GeneralErrorCode.NOT_FOUND));
        user.updateFcmToken(fcmToken);
    }

    /**
     * 사용자 프로필을 조회한다.
     *
     * @param userId JWT 필터가 주입한 사용자 ID
     * @return 사용자 프로필 전체 정보
     */
    @Transactional(readOnly = true)
    public UserProfileGetResponse getProfile(Long userId) {
        UserAccount user = userAccountRepository
                .findById(userId)
                .orElseThrow(() -> new GeneralException(GeneralErrorCode.NOT_FOUND));

        return UserConverter.toProfileGetResponse(user);
    }

    /**
     * 앱 사용 시점(회원가입)부터 현재까지의 저축현황을 조회한다.
     *
     * <p>월별 저축액 = (프로필 월소득 + 추가수입) - (프로필 고정지출 + 변동지출)
     *
     * @param userId JWT 필터가 주입한 사용자 ID
     * @return 총 저축 금액 및 월별 저축 기록
     */
    @Transactional(readOnly = true)
    public SavingsStatusResponse getSavingsStatus(Long userId) {
        UserAccount user = userAccountRepository
                .findById(userId)
                .orElseThrow(() -> new GeneralException(GeneralErrorCode.NOT_FOUND));

        YearMonth startMonth = YearMonth.from(user.getCreatedAt());
        YearMonth currentMonth = YearMonth.now(clock);

        LocalDateTime from = startMonth.atDay(1).atStartOfDay();
        LocalDateTime to = currentMonth.atEndOfMonth().atTime(LocalTime.MAX);

        // 월별 지출·수입 합계를 한 번에 조회
        Map<String, Long> expenseByMonth = toMonthMap(expenseRepository.sumExpensesGroupByMonth(userId, from, to));
        Map<String, Long> incomeByMonth = toMonthMap(expenseRepository.sumIncomeGroupByMonth(userId, from, to));

        long monthlyIncome = user.getMonthlyIncome();
        long fixedExpense = user.totalFixedExpenses();

        List<MonthlyRecord> records = new ArrayList<>();
        long totalSavings = 0;

        for (YearMonth ym = startMonth; !ym.isAfter(currentMonth); ym = ym.plusMonths(1)) {
            String key = ym.toString();
            long additionalIncome = incomeByMonth.getOrDefault(key, 0L);
            long variableExpense = expenseByMonth.getOrDefault(key, 0L);

            long savings = (monthlyIncome + additionalIncome) - (fixedExpense + variableExpense);
            totalSavings += savings;
            records.add(new MonthlyRecord(key, savings));
        }

        return new SavingsStatusResponse(totalSavings, records);
    }

    private Map<String, Long> toMonthMap(List<Object[]> rows) {
        Map<String, Long> map = new HashMap<>();
        for (Object[] row : rows) {
            int year = ((Number) row[0]).intValue();
            int month = ((Number) row[1]).intValue();
            long amount = ((Number) row[2]).longValue();
            map.put(YearMonth.of(year, month).toString(), amount);
        }
        return map;
    }
}
