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

    /** 이메일로 사용자를 조회한다. */
    Optional<UserAccount> findByEmail(String email);

    /** 이메일이 이미 존재하는지 확인한다. */
    boolean existsByEmail(String email);

    /** 소셜 로그인 제공자와 제공자 고유 ID로 사용자를 조회한다. */
    Optional<UserAccount> findByProviderAndProviderId(AuthProvider provider, String providerId);
}
