#!/usr/bin/env bash
#
# 마이그레이션을 실제 PostgreSQL 에 적용하고 스키마의 제약이 지켜지는지 확인합니다.
#
# 확인하는 것:
#   - short_id 가 바뀌지 않는가 (공유 URL 이 깨지는 것을 막는 장치)
#   - 잘못된 ISBN 이 거부되는가
#   - 같은 응답의 재적재가 멱등성 제약에 걸리는가
#   - 판정 간선이 한 방향으로만 저장되는가
#   - 색인 전환이 원자적으로 되는가
#
# 사용법:
#   DATABASE_URL=postgres://... ./scripts/verify-schema.sh    # 기존 DB 사용 (버려도 되는 것만)
#   ./scripts/verify-schema.sh                                 # 임시 인스턴스를 띄워서 확인
#
# 임시 인스턴스 방식은 PostgreSQL 서버 바이너리가 필요하고, 루트로는 돌지 않습니다.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MIGRATIONS="$ROOT/backend/src/main/resources/db/migration"
VERIFY="$ROOT/backend/src/test/resources/db/verify-schema.sql"

run_sql() { psql "$1" -q -v ON_ERROR_STOP=1 -f "$2"; }

apply_all() {
    local url="$1"
    for file in "$MIGRATIONS"/V*.sql; do
        echo "  적용: $(basename "$file")"
        run_sql "$url" "$file"
    done
    echo "  검증 실행"
    psql "$url" -q -f "$VERIFY"
}

if [ -n "${DATABASE_URL:-}" ]; then
    echo "기존 데이터베이스에 적용합니다. 버려도 되는 DB 인지 확인하세요."
    apply_all "$DATABASE_URL"
    exit 0
fi

if [ "$(id -u)" = "0" ]; then
    echo "루트로는 PostgreSQL 서버를 띄울 수 없습니다." >&2
    echo "일반 사용자로 실행하거나 DATABASE_URL 을 주세요." >&2
    exit 1
fi

PGBIN="$(ls -d /usr/lib/postgresql/*/bin 2>/dev/null | sort -V | tail -1 || true)"
[ -z "$PGBIN" ] && PGBIN="$(dirname "$(command -v postgres)")"
[ -x "$PGBIN/initdb" ] || { echo "PostgreSQL 서버 바이너리를 찾지 못했습니다." >&2; exit 1; }

WORKDIR="$(mktemp -d)"
cleanup() {
    "$PGBIN/pg_ctl" -D "$WORKDIR/data" stop -m immediate >/dev/null 2>&1 || true
    rm -rf "$WORKDIR"
}
trap cleanup EXIT

echo "임시 인스턴스를 띄웁니다: $WORKDIR"
"$PGBIN/initdb" -D "$WORKDIR/data" -U wimb --encoding=UTF8 --locale=C >/dev/null
"$PGBIN/pg_ctl" -D "$WORKDIR/data" \
    -o "-k $WORKDIR -c listen_addresses=''" -l "$WORKDIR/log" start >/dev/null

# 소켓이 열릴 때까지 기다립니다.
for _ in $(seq 1 30); do
    "$PGBIN/pg_isready" -h "$WORKDIR" -U wimb >/dev/null 2>&1 && break
    sleep 0.3
done

"$PGBIN/createdb" -h "$WORKDIR" -U wimb wimb_verify
apply_all "postgresql://wimb@/wimb_verify?host=$WORKDIR"

echo "스키마 검증을 통과했습니다."
