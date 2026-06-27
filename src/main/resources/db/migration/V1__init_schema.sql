-- =====================================================================
-- V1__init_schema.sql  (Postgres)  — 기준 스키마 PlanUs(2).sql 변환본
-- MySQL→Postgres: 백틱 제거, ENUM→VARCHAR, DATETIME→TIMESTAMP, JSON→JSONB,
--   DOUBLE→DOUBLE PRECISION, AUTO_INCREMENT→IDENTITY, user는 예약어라 "user".
-- 결함 수정: BOOLEEAN 오타, persona_templates.name의 잘못된 기본값, 값없는 ENUM.
-- =====================================================================

CREATE TABLE budget_category (
                                 id BIGINT NOT NULL,
                                 budget_id BIGINT NOT NULL,
                                 category_id BIGINT NOT NULL,
                                 amount BIGINT NULL,
                                 user_modified BOOLEAN NULL DEFAULT FALSE,
                                 created_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
                                 updated_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE "user" (
                        id BIGINT NOT NULL,
                        email VARCHAR(255) NOT NULL,
                        password VARCHAR(255) NOT NULL,
                        nickname VARCHAR(100) NULL,
                        age INT NULL,
                        gender VARCHAR(50) NULL,
                        residence VARCHAR(255) NULL,
                        monthly_income BIGINT NULL,
                        monthly_fixed_expenses BIGINT NULL,
                        monthly_savings_goal BIGINT NULL,
                        highestExpenseCategory JSONB NULL,
                        spending_habit VARCHAR(50) NULL,
                        employment_status BOOLEAN NULL,
                        home_ownership BOOLEAN NULL,
                        family_size INT NULL DEFAULT 1,
                        created_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE ai_feedbacks (
                              id BIGINT NOT NULL,
                              user_id BIGINT NOT NULL,
                              year_month DATE NOT NULL,
                              pattern_analysis JSONB NULL,
                              overall_opinion TEXT NULL,
                              hypothesis JSONB NULL,
                              advice JSONB NULL,
                              context_summary TEXT NULL,
                              is_embedded BOOLEAN NULL DEFAULT FALSE,
                              confidence DOUBLE PRECISION NULL,
                              economic_indicators_used BOOLEAN NULL,
                              created_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
                              updated_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE inquiry (
                         id BIGINT NOT NULL,
                         user_id BIGINT NOT NULL,
                         title VARCHAR(255) NULL,
                         content TEXT NULL,
                         status VARCHAR(50) NULL DEFAULT 'PENDING',
                         answer TEXT NULL,
                         answered_at TIMESTAMP NULL,
                         created_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
                         updated_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE category (
                          id BIGINT NOT NULL,
                          name VARCHAR(50) NOT NULL,
                          created_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
                          updated_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE user_reactions (
                                id BIGINT NOT NULL,
                                feedback_id BIGINT NOT NULL,
                                user_id BIGINT NOT NULL,
                                score INT NULL,
                                reaction VARCHAR(50) NULL,
                                content TEXT NULL,
                                created_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE saving_simulation_items (
                                         id BIGINT NOT NULL,
                                         simulation_id BIGINT NOT NULL,
                                         category_id BIGINT NOT NULL,
                                         saving_amount BIGINT NULL,
                                         annual_saving BIGINT NULL,
                                         suggestion VARCHAR(500) NULL,
                                         created_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE saving_simulations (
                                    id BIGINT NOT NULL,
                                    user_id BIGINT NOT NULL,
                                    feedback_id BIGINT NULL,
                                    year_month DATE NOT NULL,
                                    type VARCHAR(50) NOT NULL,
                                    is_applied BOOLEAN NULL DEFAULT FALSE,
                                    total_saving_amount BIGINT NULL,
                                    original_months INT NULL,
                                    reduced_months INT NULL,
                                    ai_raw_result JSONB NULL,
                                    created_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
                                    updated_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE expense (
                         id BIGINT NOT NULL,
                         user_id BIGINT NOT NULL,
                         category_id BIGINT NOT NULL,
                         type VARCHAR(50) NOT NULL,
                         amount BIGINT NOT NULL,
                         expense_date TIMESTAMP NOT NULL,
                         description VARCHAR(255) NULL,
                         memo TEXT NULL,
                         created_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
                         updated_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE monthly_summaries (
                                   id BIGINT NOT NULL,
                                   user_id BIGINT NOT NULL,
                                   year_month DATE NOT NULL,
                                   total_expense BIGINT NULL,
                                   total_income BIGINT NULL,
                                   summary_text TEXT NULL,
                                   is_embedded BOOLEAN NULL DEFAULT FALSE,
                                   created_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
                                   updated_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE budget (
                        id BIGINT NOT NULL,
                        user_id BIGINT NOT NULL,
                        year_month DATE NOT NULL,
                        total_budget BIGINT NULL,
                        ai_summary TEXT NULL,
                        is_confirmed BOOLEAN NULL DEFAULT FALSE,
                        created_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE advice_results (
                                id BIGINT NOT NULL,
                                improvement_rate DOUBLE PRECISION NULL,
                                is_success BOOLEAN NULL,
                                created_at TIMESTAMP NULL,
                                feedback_id BIGINT NOT NULL,
                                category_id BIGINT NOT NULL
);

CREATE TABLE persona_templates (
                                   id BIGINT NOT NULL,
                                   name VARCHAR(255) NULL,
                                   content TEXT NULL
);

CREATE TABLE notification (
                              id BIGINT NOT NULL,
                              user_id BIGINT NOT NULL,
                              budget_alert_enabled BOOLEAN NULL DEFAULT TRUE,
                              monthly_report_enabled BOOLEAN NULL DEFAULT TRUE,
                              created_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
                              updated_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE peer_comparison_stats (
                                       id BIGINT NOT NULL,
                                       category_id BIGINT NULL,
                                       age_group VARCHAR(20) NOT NULL,
                                       region VARCHAR(100) NOT NULL,
                                       family_size INT NOT NULL,
                                       income_range VARCHAR(50) NOT NULL,
                                       avg_amount BIGINT NULL,
                                       year_month DATE NOT NULL,
                                       data_source VARCHAR(255) NULL,
                                       created_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
                                       updated_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE user_personas (
                               id BIGINT NOT NULL,
                               user_id BIGINT NOT NULL,
                               template_id BIGINT NOT NULL,
                               content TEXT NULL,
                               stage VARCHAR(50) NULL,
                               version BIGINT NULL,
                               generated_by VARCHAR(100) NULL,
                               is_embedded BOOLEAN NULL,
                               created_at TIMESTAMP NULL,
                               updated_at TIMESTAMP NULL
);

ALTER TABLE budget_category ADD CONSTRAINT PK_BUDGET_CATEGORY PRIMARY KEY (
                                                                           id
    );

ALTER TABLE "user" ADD CONSTRAINT PK_USER PRIMARY KEY (
                                                       id
    );

ALTER TABLE ai_feedbacks ADD CONSTRAINT PK_AI_FEEDBACKS PRIMARY KEY (
                                                                     id
    );

ALTER TABLE inquiry ADD CONSTRAINT PK_INQUIRY PRIMARY KEY (
                                                           id
    );

ALTER TABLE category ADD CONSTRAINT PK_CATEGORY PRIMARY KEY (
                                                             id
    );

ALTER TABLE user_reactions ADD CONSTRAINT PK_USER_REACTIONS PRIMARY KEY (
                                                                         id
    );

ALTER TABLE saving_simulation_items ADD CONSTRAINT PK_SAVING_SIMULATION_ITEMS PRIMARY KEY (
                                                                                           id
    );

ALTER TABLE saving_simulations ADD CONSTRAINT PK_SAVING_SIMULATIONS PRIMARY KEY (
                                                                                 id
    );

ALTER TABLE expense ADD CONSTRAINT PK_EXPENSE PRIMARY KEY (
                                                           id
    );

ALTER TABLE monthly_summaries ADD CONSTRAINT PK_MONTHLY_SUMMARIES PRIMARY KEY (
                                                                               id
    );

ALTER TABLE budget ADD CONSTRAINT PK_BUDGET PRIMARY KEY (
                                                         id
    );

ALTER TABLE advice_results ADD CONSTRAINT PK_ADVICE_RESULTS PRIMARY KEY (
                                                                         id
    );

ALTER TABLE persona_templates ADD CONSTRAINT PK_PERSONA_TEMPLATES PRIMARY KEY (
                                                                               id
    );

ALTER TABLE notification ADD CONSTRAINT PK_NOTIFICATION PRIMARY KEY (
                                                                     id
    );

ALTER TABLE peer_comparison_stats ADD CONSTRAINT PK_PEER_COMPARISON_STATS PRIMARY KEY (
                                                                                       id
    );

ALTER TABLE user_personas ADD CONSTRAINT PK_USER_PERSONAS PRIMARY KEY (
                                                                       id
    );

-- =====================================================================
-- (보강) 자동 증가 — 기준 스키마에 누락. PK 추가 이후 IDENTITY 부여.
-- =====================================================================
ALTER TABLE budget_category ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY;
ALTER TABLE "user" ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY;
ALTER TABLE ai_feedbacks ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY;
ALTER TABLE inquiry ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY;
ALTER TABLE category ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY;
ALTER TABLE user_reactions ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY;
ALTER TABLE saving_simulation_items ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY;
ALTER TABLE saving_simulations ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY;
ALTER TABLE expense ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY;
ALTER TABLE monthly_summaries ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY;
ALTER TABLE budget ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY;
ALTER TABLE advice_results ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY;
ALTER TABLE persona_templates ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY;
ALTER TABLE notification ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY;
ALTER TABLE peer_comparison_stats ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY;
ALTER TABLE user_personas ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY;