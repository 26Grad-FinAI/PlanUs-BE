-- 같은 피드백·카테고리에 반응이 중복 저장되지 않게 한다 (#160).
-- 1) 기존 중복 정리: 같은 (사용자, 피드백, 카테고리)에서 가장 최근 것(created_at, 같으면 id가 큰 것)만 남긴다.
--    category_id가 NULL인 행(피드백 전체에 대한 반응)도 서로 같은 그룹으로 본다.
DELETE FROM user_reactions r
USING user_reactions k
WHERE r.user_id = k.user_id
  AND r.feedback_id = k.feedback_id
  AND r.category_id IS NOT DISTINCT FROM k.category_id
  AND (COALESCE(r.created_at, '-infinity'::timestamp), r.id) < (COALESCE(k.created_at, '-infinity'::timestamp), k.id);

-- 2) 유일 인덱스. NULL은 서로 다른 값으로 취급되므로 카테고리 있음/없음을 부분 인덱스 둘로 나눈다
--    (NULLS NOT DISTINCT는 PostgreSQL 15 이상만 지원해 쓰지 않는다).
CREATE UNIQUE INDEX uq_user_reactions_category
    ON user_reactions (user_id, feedback_id, category_id)
    WHERE category_id IS NOT NULL;

CREATE UNIQUE INDEX uq_user_reactions_feedback
    ON user_reactions (user_id, feedback_id)
    WHERE category_id IS NULL;
