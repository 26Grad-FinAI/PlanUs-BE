-- 카테고리 이름만 갱신한다.
-- TRUNCATE CASCADE 대신 upsert를 사용해 expense·budget_category 등 FK 참조 데이터를 보존한다.
INSERT INTO category (id, name) VALUES
    (1,  '식료품'),
    (2,  '외식'),
    (3,  '주류·담배'),
    (4,  '의류·신발'),
    (5,  '보건·건강보조식품'),
    (6,  '교통'),
    (7,  '통신'),
    (8,  '문화·여가'),
    (9,  '교육'),
    (10, '여행·숙박'),
    (11, '기타')
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name;
