package com.planus.backend.domain.auth.repository;

import com.planus.backend.domain.auth.entity.PasswordResetCode;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PasswordResetCodeRepository extends JpaRepository<PasswordResetCode, Long> {

    Optional<PasswordResetCode> findByEmailAndCodeAndVerifiedFalse(String email, String code);

    Optional<PasswordResetCode> findByEmailAndVerifiedTrue(String email);

    void deleteByEmail(String email);
}
