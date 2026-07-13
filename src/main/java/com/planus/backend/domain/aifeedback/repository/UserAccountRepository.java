package com.planus.backend.domain.aifeedback.repository;

import com.planus.backend.domain.aifeedback.entity.UserAccount;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {

    /** ID만 조회 — 배치에서 전체 엔티티 로드 대신 사용. */
    @Query("SELECT u.id FROM UserAccount u ORDER BY u.id")
    List<Long> findAllIds();
}
