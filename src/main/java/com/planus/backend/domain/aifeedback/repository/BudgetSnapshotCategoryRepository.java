package com.planus.backend.domain.aifeedback.repository;

import com.planus.backend.domain.aifeedback.entity.BudgetSnapshotCategory;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** {@link BudgetSnapshotCategory} 리포지토리. 스냅샷별 카테고리 예산 배분 조회. */
public interface BudgetSnapshotCategoryRepository extends JpaRepository<BudgetSnapshotCategory, Long> {

    /**
     * 특정 스냅샷의 카테고리별 예산 배분을 조회한다.
     *
     * @param snapshotId 스냅샷 ID
     * @return 카테고리별 예산 배분 목록
     */
    List<BudgetSnapshotCategory> findBySnapshotId(Long snapshotId);
}
