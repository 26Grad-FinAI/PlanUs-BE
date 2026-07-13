package com.planus.backend.domain.aifeedback.repository;

import com.planus.backend.domain.aifeedback.entity.BudgetSnapshot;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** {@link BudgetSnapshot} 리포지토리. 버전 관리 예산 스냅샷 조회. */
public interface BudgetSnapshotRepository extends JpaRepository<BudgetSnapshot, Long> {

    /**
     * 해당 월의 최신 버전 스냅샷을 조회한다.
     *
     * @param userId    사용자 ID
     * @param yearMonth 해당 월 1일
     * @return 최신 버전 스냅샷, 없으면 빈 Optional
     */
    Optional<BudgetSnapshot> findFirstByUserIdAndYearMonthOrderByVersionDesc(Long userId, LocalDate yearMonth);
}
