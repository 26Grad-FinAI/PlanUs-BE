-- V16: hobby VARCHAR → hobbies JSONB 배열로 변경.
ALTER TABLE "user" DROP COLUMN IF EXISTS hobby;
ALTER TABLE "user" ADD COLUMN hobbies JSONB NULL;
