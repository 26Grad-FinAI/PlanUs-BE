-- 동일 피드백에 대한 중복 적용 방지를 위한 유니크 제약 조건 추가
ALTER TABLE saving_simulations
    ADD CONSTRAINT uk_saving_sim_user_feedback UNIQUE (user_id, feedback_id);
