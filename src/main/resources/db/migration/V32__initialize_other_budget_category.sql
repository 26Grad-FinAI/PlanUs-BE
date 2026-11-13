-- 기존 월 예산에 누락된 기타 배분 0원 추가
INSERT INTO budget_category (budget_id, category_id, amount, ai_amount)
SELECT b.id, 10, 0, 0
FROM budget b
WHERE NOT EXISTS (
    SELECT 1
    FROM budget_category bc
    WHERE bc.budget_id = b.id
      AND bc.category_id = 10
);
