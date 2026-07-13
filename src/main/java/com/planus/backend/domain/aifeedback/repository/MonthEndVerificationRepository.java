package com.planus.backend.domain.aifeedback.repository;

import com.planus.backend.domain.aifeedback.entity.MonthEndVerification;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** {@link MonthEndVerification} 리포지토리. 월말 예측 검증 결과 조회. */
public interface MonthEndVerificationRepository extends JpaRepository<MonthEndVerification, Long> {

    /**
     * 특정 사용자의 특정 월 검증 결과를 조회한다.
     *
     * @param userId    사용자 ID
     * @param yearMonth 해당 월 1일
     * @return 검증 결과, 없으면 빈 Optional
     */
    Optional<MonthEndVerification> findByUserIdAndYearMonth(Long userId, LocalDate yearMonth);
}
