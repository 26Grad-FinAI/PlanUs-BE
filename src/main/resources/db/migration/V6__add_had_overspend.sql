-- [8] complianceRate 산출용: 예산 초과 여부 플래그
ALTER TABLE ai_feedbacks ADD COLUMN had_overspend BOOLEAN NOT NULL DEFAULT FALSE;
