\set ON_ERROR_STOP on
\pset format unaligned
\pset tuples_only on

-- 검증용 데이터
INSERT INTO library (lib_code, short_id, name, name_norm, lib_type, sido, sigungu,
                     latitude, longitude, homepage_url)
VALUES ('011001', 1, '국립중앙도서관', '국립중앙도서관', '공공도서관', '서울특별시', '서초구',
        37.4979, 127.0276, 'https://www.nl.go.kr'),
       ('141053', 2, '성남시중원도서관', '성남시중원도서관', '공공도서관', '경기도', '성남시',
        37.4300, 127.1500, 'https://www.snlib.go.kr');

INSERT INTO work (work_id, match_key) VALUES (1, '코스모스');
INSERT INTO book (book_id, isbn13, work_id, title, title_key_core, title_key_full,
                  author_raw, source_code, source_fetched_at)
VALUES (1, '9788983711892', 1, '코스모스', '코스모스', '코스모스',
        '칼 세이건', 'SEOJI_API', now());
UPDATE work SET rep_book_id = 1 WHERE work_id = 1;

-- 1. short_id 는 바뀌면 안 된다
DO $$
BEGIN
    UPDATE library SET short_id = 99 WHERE lib_code = '011001';
    RAISE EXCEPTION '실패: short_id 변경이 막히지 않았습니다';
EXCEPTION WHEN raise_exception THEN
    IF sqlerrm LIKE '%실패:%' THEN RAISE; END IF;
    RAISE NOTICE 'OK  short_id 변경이 차단됨';
END $$;

-- 2. 이름 등 다른 컬럼은 정상 수정된다
UPDATE library SET name = '국립중앙도서관(본관)' WHERE lib_code = '011001';
\echo 'OK  다른 컬럼은 정상 수정됨'

-- 3. 잘못된 ISBN 은 거부된다
DO $$
BEGIN
    INSERT INTO book (isbn13, title, title_key_core, title_key_full, source_code, source_fetched_at)
    VALUES ('1234567890128', 'x', 'x', 'x', 'T', now());
    RAISE EXCEPTION '실패: 접두가 978/979 가 아닌 ISBN 이 통과했습니다';
EXCEPTION WHEN check_violation THEN
    RAISE NOTICE 'OK  잘못된 ISBN 접두가 거부됨';
END $$;

-- 4. 같은 응답을 다시 적재하면 멱등성 제약이 막는다
INSERT INTO ingest_run (source_code, scope_key, mode, status, input_sha256)
VALUES ('SEOJI_API', '2024-01', 'BACKFILL', 'SUCCEEDED', 'abc123');
DO $$
BEGIN
    INSERT INTO ingest_run (source_code, scope_key, mode, status, input_sha256)
    VALUES ('SEOJI_API', '2024-01', 'BACKFILL', 'SUCCEEDED', 'abc123');
    RAISE EXCEPTION '실패: 같은 해시가 두 번 들어갔습니다';
EXCEPTION WHEN unique_violation THEN
    RAISE NOTICE 'OK  같은 응답의 재적재가 건너뛰기로 걸림';
END $$;

-- 5. 판정 간선은 한 방향으로만 저장된다
DO $$
BEGIN
    INSERT INTO bib_merge_edge (a_isbn13, b_isbn13, rule_code, confidence, verdict)
    VALUES ('9788983711899', '9788983711892', 'R3_TITLE_AUTHOR', 0.95, 'MERGE');
    RAISE EXCEPTION '실패: 역순 쌍이 통과했습니다';
EXCEPTION WHEN check_violation THEN
    RAISE NOTICE 'OK  역순 쌍이 거부되어 관계가 두 행으로 갈라지지 않음';
END $$;

-- 6. 소장 캐시와 도서관 교집합 조회
INSERT INTO holding_cache (isbn13, region_code, lib_codes)
VALUES ('9788983711892', 'ALL', ARRAY['011001', '141053', '999999']);

\echo -n 'OK  선택한 도서관과의 교집합: '
SELECT array_to_string(ARRAY(
    SELECT l.name FROM library l
     WHERE l.lib_code = ANY (
        SELECT unnest(lib_codes) FROM holding_cache WHERE isbn13 = '9788983711892')
     ORDER BY l.short_id), ', ');

-- 7. 색인과 검색
INSERT INTO search_doc_v1 (work_id, title_display, author_display, isbn13_list,
                           search_text, search_bigrams)
VALUES (1, '코스모스', '칼 세이건', ARRAY['9788983711892']::varchar(13)[],
        '코스모스칼세이건', '코스 스모 모스 스칼 칼세 세이 이건');

\echo -n 'OK  바이그램 검색: '
SELECT count(*) FROM search_doc
 WHERE to_tsvector('simple', search_bigrams) @@ to_tsquery('simple', '코스 & 스모 & 모스');

\echo -n 'OK  ISBN 배열 조회: '
SELECT count(*) FROM search_doc WHERE '9788983711892' = ANY (isbn13_list);

-- 8. 무중단 전환
CREATE TABLE search_doc_v2 (LIKE search_doc_v1 INCLUDING ALL);
INSERT INTO search_doc_v2 SELECT * FROM search_doc_v1;
INSERT INTO search_doc_v2 (work_id, title_display, isbn13_list, search_text, search_bigrams)
VALUES (2, '데미안', ARRAY['9788937460449']::varchar(13)[], '데미안', '데미 미안');

BEGIN;
SELECT promote_search_doc('search_doc_v2');
COMMIT;

\echo -n 'OK  전환 후 문서 수(2 여야 함): '
SELECT count(*) FROM search_doc;

\echo -n 'OK  전환 이력: '
SELECT string_agg(table_name || '=' || CASE WHEN retired_at IS NULL THEN 'live' ELSE 'retired' END,
                  ', ' ORDER BY table_name)
  FROM search_doc_generation;

-- 9. 없는 테이블로 전환하면 막힌다
DO $$
BEGIN
    PERFORM promote_search_doc('search_doc_v9');
    RAISE EXCEPTION '실패: 없는 테이블로 전환되었습니다';
EXCEPTION WHEN raise_exception THEN
    IF sqlerrm LIKE '실패:%' THEN RAISE; END IF;
    RAISE NOTICE 'OK  없는 테이블로의 전환이 차단됨';
END $$;

\echo '--- 검증 완료 ---'
