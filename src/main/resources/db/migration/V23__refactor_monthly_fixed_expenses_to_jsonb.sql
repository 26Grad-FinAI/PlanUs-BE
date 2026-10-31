-- 기존 bigint 값을 jsonb 배열로 마이그레이션
ALTER TABLE "user"
    ALTER COLUMN monthly_fixed_expenses TYPE jsonb
    USING CASE
        WHEN monthly_fixed_expenses IS NULL OR monthly_fixed_expenses = 0
            THEN '[]'::jsonb
        ELSE jsonb_build_array(jsonb_build_object('name', '기타', 'amount', monthly_fixed_expenses))
    END;

-- 기본값 설정
ALTER TABLE "user"
    ALTER COLUMN monthly_fixed_expenses SET DEFAULT '[]'::jsonb;
