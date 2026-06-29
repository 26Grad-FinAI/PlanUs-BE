package com.planus.backend.domain.aifeedback.repo;

import com.planus.backend.domain.aifeedback.entity.UserReaction;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface UserReactionRepository extends JpaRepository<UserReaction, Long> {
    List<UserReaction> findByUserId(Long userId);
}
