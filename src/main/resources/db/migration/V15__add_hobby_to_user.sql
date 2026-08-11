-- V15: user 테이블에 hobby 컬럼 추가.
-- home_ownership 은 V1 스키마에 이미 존재하므로 생략.
ALTER TABLE "user" ADD COLUMN IF NOT EXISTS hobby VARCHAR(255) NULL;
