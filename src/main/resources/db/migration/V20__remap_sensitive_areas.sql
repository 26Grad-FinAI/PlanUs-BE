-- V20__remap_sensitive_areas.sql
-- user_profile.sensitive_areas JSONB 내 카테고리 ID를 새 체계로 리매핑
--
-- 기존 → 신규 매핑:
--   1→1, 2→7, 3→7, 4→2, 5→3, 6→4, 7→9, 8→5, 9→6, 10→8, 11→10

UPDATE user_profile
SET sensitive_areas = (
    SELECT COALESCE(jsonb_agg(DISTINCT mapped ORDER BY mapped), '[]'::jsonb)
    FROM jsonb_array_elements(sensitive_areas) AS elem,
    LATERAL (
        SELECT CASE elem::int
            WHEN 1 THEN 1
            WHEN 2 THEN 7
            WHEN 3 THEN 7
            WHEN 4 THEN 2
            WHEN 5 THEN 3
            WHEN 6 THEN 4
            WHEN 7 THEN 9
            WHEN 8 THEN 5
            WHEN 9 THEN 6
            WHEN 10 THEN 8
            WHEN 11 THEN 10
            ELSE elem::int
        END AS mapped
    ) t
)
WHERE sensitive_areas != '[]'::jsonb;
