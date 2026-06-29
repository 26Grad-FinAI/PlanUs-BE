package com.planus.backend.domain.aifeedback.repo;

import com.planus.backend.domain.aifeedback.entity.AiFeedback;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiFeedbackRepository extends JpaRepository<AiFeedback, Long> {
}
