package com.planus.backend.domain.aifeedback.repository;

import com.planus.backend.domain.aifeedback.entity.UserProfile;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** {@link UserProfile} 리포지토리. 누적 해석 프로필 조회·갱신. */
public interface UserProfileRepository extends JpaRepository<UserProfile, Long> {

    /**
     * 사용자 ID로 누적 프로필을 조회한다.
     *
     * @param userId 사용자 ID
     * @return 프로필, 없으면 빈 Optional (신규 사용자)
     */
    Optional<UserProfile> findByUserId(Long userId);
}
