package com.planus.backend.domain.user.repository;

import com.planus.backend.domain.user.entity.AuthProvider;
import com.planus.backend.domain.user.entity.UserAccount;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {

    /** ID만 조회 — 배치에서 전체 엔티티 로드 대신 사용. */
    @Query("SELECT u.id FROM UserAccount u ORDER BY u.id")
    List<Long> findAllIds();

    Optional<UserAccount> findByEmail(String email);

    boolean existsByEmail(String email);

    Optional<UserAccount> findByProviderAndProviderId(AuthProvider provider, String providerId);
}
