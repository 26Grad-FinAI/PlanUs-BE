-- V16: hobby VARCHAR → hobbies JSONB 배열로 변경.
-- 기존 hobby 값을 JSONB 배열로 복사한 뒤 원본 컬럼을 제거한다.
ALTER TABLE "user" ADD COLUMN hobbies JSONB NULL;

UPDATE "user"
SET hobbies = CASE
    WHEN hobby IS NULL THEN NULL
    ELSE jsonb_build_array(hobby)
END;

ALTER TABLE "user" DROP COLUMN hobby;
