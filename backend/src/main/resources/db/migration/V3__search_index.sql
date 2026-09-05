-- 검색 색인과 무중단 전환.
--
-- Elasticsearch 의 별칭에 해당하는 것을 뷰로 구현합니다. 애플리케이션은 언제나
-- search_doc 만 조회하고 실제 테이블 이름은 알지 못합니다.

CREATE TABLE search_doc_v1 (
    work_id           integer       PRIMARY KEY,
    title_display     text          NOT NULL,
    author_display    text,
    publisher_display text,
    pub_date          date,
    price             integer,
    isbn13_list       varchar(13)[] NOT NULL,
    edition_labels    text[]        NOT NULL DEFAULT '{}',
    search_text       text          NOT NULL,
    search_bigrams    text          NOT NULL
);

COMMENT ON COLUMN search_doc_v1.isbn13_list IS
    '이 테이블에서 가장 중요한 컬럼입니다. 저작에 묶인 모든 판본의 ISBN 이 들어 있고, '
    '소장 조회는 이 목록 전체를 대상으로 합니다. 판본 하나만 조회하면 도서관이 다른 판을 '
    '가지고 있어도 미소장으로 나옵니다.';
COMMENT ON COLUMN search_doc_v1.search_text IS
    '정규화된 검색 대상 텍스트. 정규화는 BibNormalizer 한 곳에서만 하고 적재와 질의가 '
    '같은 함수를 부릅니다. 서로 다른 정규화를 적용하면 색인과 질의가 조용히 어긋납니다.';
COMMENT ON COLUMN search_doc_v1.search_bigrams IS
    'search_text 를 2글자씩 잘라 공백으로 이은 값. PGroonga 를 쓸 수 없게 되었을 때를 '
    '대비한 대체 경로입니다. 두 컬럼을 항상 함께 채우므로 전환이 색인 교체만으로 끝나고 '
    '재적재가 필요하지 않습니다.';

CREATE INDEX search_doc_v1_isbn_idx ON search_doc_v1 USING gin (isbn13_list);

-- 오타와 띄어쓰기 오류를 흡수하는 마지막 단계용입니다. 전문 검색이 0건일 때만 씁니다.
CREATE INDEX search_doc_v1_trgm_idx ON search_doc_v1 USING gin (search_text gin_trgm_ops);

-- 전문 검색 색인은 환경에 따라 갈립니다.
-- Supabase 에는 PGroonga 가 있고 로컬 개발 환경에는 대개 없습니다.
-- 두 경우 모두에서 마이그레이션이 돌아가야 하므로 여기서 갈라 놓습니다.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_available_extensions WHERE name = 'pgroonga') THEN
        CREATE EXTENSION IF NOT EXISTS pgroonga;
        EXECUTE 'CREATE INDEX search_doc_v1_pgroonga_idx '
             || 'ON search_doc_v1 USING pgroonga (search_text)';
        RAISE NOTICE 'PGroonga 색인을 만들었습니다.';
    ELSE
        EXECUTE 'CREATE INDEX search_doc_v1_bigram_idx '
             || 'ON search_doc_v1 USING gin (to_tsvector(''simple'', search_bigrams))';
        RAISE NOTICE 'PGroonga 가 없어 자체 생성 바이그램 색인으로 대체했습니다. '
                     '검색 품질이 조금 낮아지지만 동작에는 문제가 없습니다.';
    END IF;
END $$;

-- 애플리케이션이 조회하는 유일한 이름입니다.
CREATE VIEW search_doc AS SELECT * FROM search_doc_v1;

-- ---------------------------------------------------------------
-- 세대 관리와 전환
-- ---------------------------------------------------------------

CREATE TABLE search_doc_generation (
    table_name  text        PRIMARY KEY,
    built_at    timestamptz NOT NULL DEFAULT now(),
    doc_count   integer,
    promoted_at timestamptz,
    retired_at  timestamptz
);

COMMENT ON TABLE search_doc_generation IS
    '어느 세대가 언제 서비스에 올라갔는지 남깁니다. 이전 세대를 두 개까지만 남기고 '
    '정리할 때 이 표를 봅니다.';

INSERT INTO search_doc_generation (table_name, promoted_at)
VALUES ('search_doc_v1', now());

-- 새 세대로 원자적으로 갈아 끼웁니다.
--
-- 반드시 검증을 통과한 뒤에만 부르세요. 문서 수와 고정 질의를 확인하지 않고
-- 전환하면, 자동 배치가 조용히 망가진 색인으로 갈아 끼우게 됩니다.
-- 이 구조에서 가장 위험한 실패입니다.
CREATE FUNCTION promote_search_doc(new_table text) RETURNS void AS $$
BEGIN
    IF to_regclass(new_table) IS NULL THEN
        RAISE EXCEPTION '% 테이블이 없습니다. 색인을 먼저 만드세요.', new_table;
    END IF;

    -- 함수 본문은 호출자의 트랜잭션 안에서 돌기 때문에 이 두 문장은 원자적입니다.
    -- 조회 쪽에서 보면 잠금이 밀리초 단위라 사실상 끊기지 않습니다.
    -- 컬럼 구성이 바뀌어도 되도록 REPLACE 가 아니라 DROP 후 CREATE 로 합니다.
    EXECUTE 'DROP VIEW IF EXISTS search_doc';
    EXECUTE format('CREATE VIEW search_doc AS SELECT * FROM %I', new_table);

    INSERT INTO search_doc_generation (table_name, promoted_at)
    VALUES (new_table, now())
    ON CONFLICT (table_name) DO UPDATE SET promoted_at = now();

    UPDATE search_doc_generation
       SET retired_at = now()
     WHERE table_name <> new_table AND retired_at IS NULL;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION promote_search_doc(text) IS
    '검증을 통과한 새 색인으로 전환합니다. 실패하면 새 테이블을 버리고 기존 뷰를 그대로 두세요.';
