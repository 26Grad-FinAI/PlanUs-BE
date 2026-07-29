-- MAPE 컬럼을 nullable로 변경한다.
-- 예측값이 없는 경우(predictedTotal = 0) 0.0("완벽 예측")으로 오독되는 것을 방지.
ALTER TABLE month_end_verification
    ALTER COLUMN mape DROP NOT NULL;
