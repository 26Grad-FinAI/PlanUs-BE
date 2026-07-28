-- 기존 카테고리 전체 삭제 후 재정의
TRUNCATE TABLE category RESTART IDENTITY CASCADE;

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
    (11, '기타');
