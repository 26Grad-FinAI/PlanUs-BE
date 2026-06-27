-- =====================================================================
-- V3__add_foreign_keys.sql  — 참조 무결성(FK) 제약 추가
-- V1·V2에서 누락된 FK를 보강한다.
-- =====================================================================

-- expense
ALTER TABLE expense
    ADD CONSTRAINT fk_expense_user
        FOREIGN KEY (user_id) REFERENCES "user" (id),
    ADD CONSTRAINT fk_expense_category
        FOREIGN KEY (category_id) REFERENCES category (id);

-- budget
ALTER TABLE budget
    ADD CONSTRAINT fk_budget_user
        FOREIGN KEY (user_id) REFERENCES "user" (id);

-- budget_category
ALTER TABLE budget_category
    ADD CONSTRAINT fk_budget_category_budget
        FOREIGN KEY (budget_id) REFERENCES budget (id),
    ADD CONSTRAINT fk_budget_category_category
        FOREIGN KEY (category_id) REFERENCES category (id);

-- ai_feedbacks
ALTER TABLE ai_feedbacks
    ADD CONSTRAINT fk_ai_feedbacks_user
        FOREIGN KEY (user_id) REFERENCES "user" (id),
    ADD CONSTRAINT fk_ai_feedbacks_category
        FOREIGN KEY (category_id) REFERENCES category (id);

-- user_reactions
ALTER TABLE user_reactions
    ADD CONSTRAINT fk_user_reactions_feedback
        FOREIGN KEY (feedback_id) REFERENCES ai_feedbacks (id),
    ADD CONSTRAINT fk_user_reactions_user
        FOREIGN KEY (user_id) REFERENCES "user" (id),
    ADD CONSTRAINT fk_user_reactions_category
        FOREIGN KEY (category_id) REFERENCES category (id);

-- saving_simulations
ALTER TABLE saving_simulations
    ADD CONSTRAINT fk_saving_simulations_user
        FOREIGN KEY (user_id) REFERENCES "user" (id),
    ADD CONSTRAINT fk_saving_simulations_feedback
        FOREIGN KEY (feedback_id) REFERENCES ai_feedbacks (id);

-- saving_simulation_items
ALTER TABLE saving_simulation_items
    ADD CONSTRAINT fk_saving_simulation_items_simulation
        FOREIGN KEY (simulation_id) REFERENCES saving_simulations (id),
    ADD CONSTRAINT fk_saving_simulation_items_category
        FOREIGN KEY (category_id) REFERENCES category (id);

-- monthly_summaries
ALTER TABLE monthly_summaries
    ADD CONSTRAINT fk_monthly_summaries_user
        FOREIGN KEY (user_id) REFERENCES "user" (id);

-- inquiry
ALTER TABLE inquiry
    ADD CONSTRAINT fk_inquiry_user
        FOREIGN KEY (user_id) REFERENCES "user" (id);

-- notification
ALTER TABLE notification
    ADD CONSTRAINT fk_notification_user
        FOREIGN KEY (user_id) REFERENCES "user" (id);

-- advice_results
ALTER TABLE advice_results
    ADD CONSTRAINT fk_advice_results_feedback
        FOREIGN KEY (feedback_id) REFERENCES ai_feedbacks (id),
    ADD CONSTRAINT fk_advice_results_category
        FOREIGN KEY (category_id) REFERENCES category (id);

-- peer_comparison_stats
ALTER TABLE peer_comparison_stats
    ADD CONSTRAINT fk_peer_comparison_stats_category
        FOREIGN KEY (category_id) REFERENCES category (id);

-- user_personas
ALTER TABLE user_personas
    ADD CONSTRAINT fk_user_personas_user
        FOREIGN KEY (user_id) REFERENCES "user" (id),
    ADD CONSTRAINT fk_user_personas_template
        FOREIGN KEY (template_id) REFERENCES persona_templates (id);

-- memo_embedding (V2에서 expense FK는 이미 존재, user·category 추가)
ALTER TABLE memo_embedding
    ADD CONSTRAINT fk_memo_embedding_user
        FOREIGN KEY (user_id) REFERENCES "user" (id),
    ADD CONSTRAINT fk_memo_embedding_category
        FOREIGN KEY (category_id) REFERENCES category (id);

-- V2에서 category id 1~11을 직접 삽입했으나 identity sequence가 갱신되지 않음.
-- 시퀀스를 max(id)로 보정하여 이후 INSERT 시 PK 충돌을 방지한다.
SELECT setval(pg_get_serial_sequence('category', 'id'), (SELECT MAX(id) FROM category), true);
