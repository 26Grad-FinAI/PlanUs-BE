package com.planus.backend.domain.aifeedback.repository;

import com.planus.backend.domain.aifeedback.entity.UserReaction;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** 사용자 피드백 반응 리포지토리. */
public interface UserReactionRepository extends JpaRepository<UserReaction, Long> {

    /** 특정 사용자의 전체 반응 목록을 조회한다. */
    List<UserReaction> findByUserId(Long userId);
}
