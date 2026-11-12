-- 기존 월 예산에 누락된 기타 배분 0원 추가
INSERT INTO budget_category (budget_id, category_id, amount, ai_amount)
SELECT id, 10, 0, 0
FROM budget
ON CONFLICT (budget_id, category_id) DO NOTHING;
