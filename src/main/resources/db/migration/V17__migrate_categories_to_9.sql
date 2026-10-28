-- =====================================================================
-- V17__migrate_categories_to_9.sql
-- 카테고리 11개 → 9개(+기타) 통일 마이그레이션.
--
-- 매핑:
--   1(식료품)→1, 2(외식)→7(식대), 3(주류·담배)→7(식대),
--   4(의류·신발)→2(의류신발), 5(보건)→3(보건), 6(교통)→4(교통),
--   7(통신)→9(정보통신), 8(문화·여가)→5(오락문화), 9(교육)→6(교육),
--   10(여행·숙박)→8(숙박여행), 11(기타)→10(기타)
-- =====================================================================

-- 0) category 테이블을 참조하는 FK 제약조건 일시 해제
ALTER TABLE expense DROP CONSTRAINT IF EXISTS fk_expense_category;
ALTER TABLE budget_category DROP CONSTRAINT IF EXISTS fk_budget_category_category;
ALTER TABLE ai_feedbacks DROP CONSTRAINT IF EXISTS fk_ai_feedbacks_category;
ALTER TABLE user_reactions DROP CONSTRAINT IF EXISTS fk_user_reactions_category;
ALTER TABLE saving_simulation_items DROP CONSTRAINT IF EXISTS fk_saving_simulation_items_category;
ALTER TABLE advice_results DROP CONSTRAINT IF EXISTS fk_advice_results_category;
ALTER TABLE peer_comparison_stats DROP CONSTRAINT IF EXISTS fk_peer_comparison_stats_category;
ALTER TABLE budget_snapshot_category DROP CONSTRAINT IF EXISTS budget_snapshot_category_category_id_fkey;
ALTER TABLE memo_question_queue DROP CONSTRAINT IF EXISTS memo_question_queue_category_id_fkey;

-- 1) expense 테이블 category_id 변환
UPDATE expense SET category_id = CASE category_id
    WHEN 1  THEN 1
    WHEN 2  THEN 7
    WHEN 3  THEN 7
    WHEN 4  THEN 2
    WHEN 5  THEN 3
    WHEN 6  THEN 4
    WHEN 7  THEN 9
    WHEN 8  THEN 5
    WHEN 9  THEN 6
    WHEN 10 THEN 8
    WHEN 11 THEN 10
    ELSE category_id
END;

-- 2) budget_snapshot_category 테이블 category_id 변환
-- 같은 snapshot_id + 새 category_id로 중복이 생길 수 있으므로 합산 후 처리
CREATE TEMP TABLE tmp_bsc_merged AS
SELECT snapshot_id,
       CASE category_id
           WHEN 1  THEN 1
           WHEN 2  THEN 7
           WHEN 3  THEN 7
           WHEN 4  THEN 2
           WHEN 5  THEN 3
           WHEN 6  THEN 4
           WHEN 7  THEN 9
           WHEN 8  THEN 5
           WHEN 9  THEN 6
           WHEN 10 THEN 8
           WHEN 11 THEN 10
           ELSE category_id
       END AS new_category_id,
       SUM(amount) AS total_amount
FROM budget_snapshot_category
GROUP BY snapshot_id, new_category_id;

DELETE FROM budget_snapshot_category;
INSERT INTO budget_snapshot_category (snapshot_id, category_id, amount)
SELECT snapshot_id, new_category_id, total_amount FROM tmp_bsc_merged;

DROP TABLE tmp_bsc_merged;

-- 3) budget_category 테이블 category_id 변환
CREATE TEMP TABLE tmp_bc_merged AS
SELECT budget_id,
       CASE category_id
           WHEN 1  THEN 1
           WHEN 2  THEN 7
           WHEN 3  THEN 7
           WHEN 4  THEN 2
           WHEN 5  THEN 3
           WHEN 6  THEN 4
           WHEN 7  THEN 9
           WHEN 8  THEN 5
           WHEN 9  THEN 6
           WHEN 10 THEN 8
           WHEN 11 THEN 10
           ELSE category_id
       END AS new_category_id,
       SUM(amount) AS total_amount
FROM budget_category
GROUP BY budget_id, new_category_id;

DELETE FROM budget_category;
INSERT INTO budget_category (budget_id, category_id, amount)
SELECT budget_id, new_category_id, total_amount FROM tmp_bc_merged;

DROP TABLE tmp_bc_merged;

-- 4) saving_simulation_items 테이블 category_id 변환
UPDATE saving_simulation_items SET category_id = CASE category_id
    WHEN 1  THEN 1
    WHEN 2  THEN 7
    WHEN 3  THEN 7
    WHEN 4  THEN 2
    WHEN 5  THEN 3
    WHEN 6  THEN 4
    WHEN 7  THEN 9
    WHEN 8  THEN 5
    WHEN 9  THEN 6
    WHEN 10 THEN 8
    WHEN 11 THEN 10
    ELSE category_id
END;

-- 5) advice_results 테이블 category_id 변환
UPDATE advice_results SET category_id = CASE category_id
    WHEN 1  THEN 1
    WHEN 2  THEN 7
    WHEN 3  THEN 7
    WHEN 4  THEN 2
    WHEN 5  THEN 3
    WHEN 6  THEN 4
    WHEN 7  THEN 9
    WHEN 8  THEN 5
    WHEN 9  THEN 6
    WHEN 10 THEN 8
    WHEN 11 THEN 10
    ELSE category_id
END;

-- 6) memo_question_queue 테이블 category_id 변환
UPDATE memo_question_queue SET category_id = CASE category_id
    WHEN 1  THEN 1
    WHEN 2  THEN 7
    WHEN 3  THEN 7
    WHEN 4  THEN 2
    WHEN 5  THEN 3
    WHEN 6  THEN 4
    WHEN 7  THEN 9
    WHEN 8  THEN 5
    WHEN 9  THEN 6
    WHEN 10 THEN 8
    WHEN 11 THEN 10
    ELSE category_id
END;

-- 7) peer_comparison_stats 테이블 category_id 변환
UPDATE peer_comparison_stats SET category_id = CASE category_id
    WHEN 1  THEN 1
    WHEN 2  THEN 7
    WHEN 3  THEN 7
    WHEN 4  THEN 2
    WHEN 5  THEN 3
    WHEN 6  THEN 4
    WHEN 7  THEN 9
    WHEN 8  THEN 5
    WHEN 9  THEN 6
    WHEN 10 THEN 8
    WHEN 11 THEN 10
    ELSE category_id
END;

-- 8) ai_feedbacks 테이블 category_id 변환 (nullable이므로 null 보존)
UPDATE ai_feedbacks SET category_id = CASE category_id
    WHEN 1  THEN 1
    WHEN 2  THEN 7
    WHEN 3  THEN 7
    WHEN 4  THEN 2
    WHEN 5  THEN 3
    WHEN 6  THEN 4
    WHEN 7  THEN 9
    WHEN 8  THEN 5
    WHEN 9  THEN 6
    WHEN 10 THEN 8
    WHEN 11 THEN 10
    ELSE category_id
END WHERE category_id IS NOT NULL;

-- 9) user_reactions 테이블 category_id 변환 (nullable이므로 null 보존)
UPDATE user_reactions SET category_id = CASE category_id
    WHEN 1  THEN 1
    WHEN 2  THEN 7
    WHEN 3  THEN 7
    WHEN 4  THEN 2
    WHEN 5  THEN 3
    WHEN 6  THEN 4
    WHEN 7  THEN 9
    WHEN 8  THEN 5
    WHEN 9  THEN 6
    WHEN 10 THEN 8
    WHEN 11 THEN 10
    ELSE category_id
END WHERE category_id IS NOT NULL;

-- 10) category 테이블 재정의 (9개 + 기타)
DELETE FROM category;
INSERT INTO category (id, name) VALUES
    (1,  '식료품'),
    (2,  '의류신발'),
    (3,  '보건'),
    (4,  '교통'),
    (5,  '오락문화'),
    (6,  '교육'),
    (7,  '식대'),
    (8,  '숙박여행'),
    (9,  '정보통신'),
    (10, '기타');

-- identity sequence 보정
SELECT setval(pg_get_serial_sequence('category', 'id'), (SELECT MAX(id) FROM category), true);

-- 11) FK 제약조건 재생성
ALTER TABLE expense
    ADD CONSTRAINT fk_expense_category FOREIGN KEY (category_id) REFERENCES category (id);
ALTER TABLE budget_category
    ADD CONSTRAINT fk_budget_category_category FOREIGN KEY (category_id) REFERENCES category (id);
ALTER TABLE ai_feedbacks
    ADD CONSTRAINT fk_ai_feedbacks_category FOREIGN KEY (category_id) REFERENCES category (id);
ALTER TABLE user_reactions
    ADD CONSTRAINT fk_user_reactions_category FOREIGN KEY (category_id) REFERENCES category (id);
ALTER TABLE saving_simulation_items
    ADD CONSTRAINT fk_saving_simulation_items_category FOREIGN KEY (category_id) REFERENCES category (id);
ALTER TABLE advice_results
    ADD CONSTRAINT fk_advice_results_category FOREIGN KEY (category_id) REFERENCES category (id);
ALTER TABLE peer_comparison_stats
    ADD CONSTRAINT fk_peer_comparison_stats_category FOREIGN KEY (category_id) REFERENCES category (id);
ALTER TABLE budget_snapshot_category
    ADD CONSTRAINT fk_budget_snapshot_category_category FOREIGN KEY (category_id) REFERENCES category (id);
ALTER TABLE memo_question_queue
    ADD CONSTRAINT fk_memo_question_queue_category FOREIGN KEY (category_id) REFERENCES category (id);
