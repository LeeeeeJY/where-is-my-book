#!/usr/bin/env bash
#
# 0단계 사전 검증 스크립트
#
# 구현계획 1절의 "확인하지 못한 사항" 다섯 가지를 실제 호출로 확인합니다.
# 인증키를 발급받은 뒤 .env를 채우고 이 스크립트를 실행하세요.
#
#   cp .env.example .env    # 키를 채웁니다
#   ./scripts/probe-apis.sh
#
# 결과는 probe-out/ 에 쌓이고 probe-out/REPORT.md 에 요약이 생깁니다.
# 원본 응답을 그대로 남기므로 나중에 파서를 만들 때 그대로 씁니다.
#
# 호출량은 전부 합쳐 30회 안팎이라 일일 한도에 영향을 주지 않습니다.

set -uo pipefail

OUT_DIR="${OUT_DIR:-probe-out}"
REPORT="$OUT_DIR/REPORT.md"
D4L_BASE="https://data4library.kr/api"
SEOJI_BASE="https://www.nl.go.kr/seoji/SearchApi.do"
SLEEP_BETWEEN="${SLEEP_BETWEEN:-0.5}"   # 호출 간격. 상대 서버에 대한 예의입니다.

# ---------- 준비 ----------

if [ -f .env ]; then
  set -a; . ./.env; set +a
fi

: "${D4L_AUTH_KEY:?.env에 D4L_AUTH_KEY가 필요합니다}"
: "${SEOJI_CERT_KEY:?.env에 SEOJI_CERT_KEY가 필요합니다}"

mkdir -p "$OUT_DIR"
: > "$REPORT"

note() { printf '%s\n' "$*" >> "$REPORT"; }
say()  { printf '\033[1m%s\033[0m\n' "$*"; }

# fetch <파일이름> <URL> — 응답을 저장하고 HTTP 코드와 소요 시간을 출력합니다.
# 키가 로그에 남지 않도록 URL은 출력하지 않습니다.
fetch() {
  local name="$1" url="$2"
  local meta
  meta=$(curl -sS -o "$OUT_DIR/$name" -w '%{http_code} %{time_total}' \
              --max-time 20 "$url" 2>"$OUT_DIR/$name.err") || meta="000 0"
  sleep "$SLEEP_BETWEEN"
  printf '%s' "$meta"
}

# 응답에서 첫 번째로 나오는 태그/키 값을 꺼냅니다. XML과 JSON 모두 대응합니다.
first_value() {
  local file="$1" key="$2"
  sed -n "s/.*<${key}>\(<!\[CDATA\[\)\?\([^]<]*\).*/\2/p" "$file" 2>/dev/null | head -1 \
    || true
}

note "# 0단계 API 검증 결과"
note ""
note "실행 시각: $(date '+%Y-%m-%d %H:%M:%S %Z')"
note ""

# ---------- 1. srchBooks: 검색으로 실재하는 ISBN을 하나 확보합니다 ----------
# 임의의 ISBN을 지어내면 "결과 없음"과 "파라미터 오류"를 구분할 수 없으므로,
# 정보나루가 실제로 알고 있는 책을 먼저 받아 그것으로 나머지를 검증합니다.

say "[1/6] srchBooks 로 검증용 ISBN 확보"
m=$(fetch "01-srchBooks.xml" "$D4L_BASE/srchBooks?authKey=$D4L_AUTH_KEY&keyword=코스모스&pageNo=1&pageSize=5")
code=${m%% *}; t=${m##* }
note "## 1. srchBooks (제목 키워드 검색)"
note ""
note "- HTTP \`$code\`, ${t}초"
note "- 원본: \`$OUT_DIR/01-srchBooks.xml\`"

TEST_ISBN=$(first_value "$OUT_DIR/01-srchBooks.xml" "isbn13")
[ -z "$TEST_ISBN" ] && TEST_ISBN=$(first_value "$OUT_DIR/01-srchBooks.xml" "isbn")

if [ -n "$TEST_ISBN" ]; then
  note "- 검증용 ISBN: \`$TEST_ISBN\`"
else
  note "- **ISBN을 뽑지 못했습니다.** 응답 구조를 직접 확인하고 아래 항목은 수동으로 진행하세요."
fi
note ""
note "응답에 들어 있는 필드 이름:"
note ""
note '```'
grep -o '<[a-zA-Z_][a-zA-Z0-9_]*>' "$OUT_DIR/01-srchBooks.xml" 2>/dev/null \
  | sort -u | tr -d '<>' | tr '\n' ' ' | fold -w 100 -s >> "$REPORT"
note ""
note '```'
note ""

# ---------- 2. libSrchByBook: region 이 필수인가 (가장 중요) ----------
# 이 답에 따라 호출량이 ISBN당 1회가 되거나 시도 수만큼 곱해집니다.

say "[2/6] libSrchByBook — region 생략 가능 여부 (핵심 항목)"
note "## 2. libSrchByBook 의 region 파라미터 — 가장 중요한 항목"
note ""

if [ -n "$TEST_ISBN" ]; then
  m=$(fetch "02a-libByBook-noregion.xml" "$D4L_BASE/libSrchByBook?authKey=$D4L_AUTH_KEY&isbn=$TEST_ISBN")
  code_no=${m%% *}
  m=$(fetch "02b-libByBook-region11.xml" "$D4L_BASE/libSrchByBook?authKey=$D4L_AUTH_KEY&isbn=$TEST_ISBN&region=11")
  code_rg=${m%% *}

  n_no=$(grep -c '<lib>' "$OUT_DIR/02a-libByBook-noregion.xml" 2>/dev/null || echo 0)
  n_rg=$(grep -c '<lib>' "$OUT_DIR/02b-libByBook-region11.xml" 2>/dev/null || echo 0)

  note "| 호출 | HTTP | 반환된 도서관 수 |"
  note "|---|---|---|"
  note "| region 없이 | \`$code_no\` | $n_no |"
  note "| region=11 (서울로 추정) | \`$code_rg\` | $n_rg |"
  note ""

  if [ "$code_no" = "200" ] && [ "$n_no" -gt "$n_rg" ] 2>/dev/null; then
    note "**판정: region 은 선택 항목이고 생략하면 전국을 돌려주는 것으로 보입니다.**"
    note "→ 소장 조회가 ISBN당 1회로 끝납니다. 계획의 호출량 추정 중 가장 낙관적인 경우입니다."
  elif [ "$code_no" = "200" ] && [ "$n_no" -eq 0 ] 2>/dev/null && [ "$n_rg" -gt 0 ] 2>/dev/null; then
    note "**판정: region 이 사실상 필수입니다.** 생략하면 200을 주면서 빈 결과가 옵니다."
    note "→ 선택한 도서관이 걸친 시도 수만큼 호출이 곱해집니다. 구현계획 16절의 네 번째 위험이 현실이 됩니다."
  else
    note "**판정 보류.** 두 응답을 직접 비교하세요. 오류 메시지가 있는지부터 확인합니다."
  fi
  note ""
  note "응답에 들어 있는 필드 이름:"
  note ""
  note '```'
  grep -o '<[a-zA-Z_][a-zA-Z0-9_]*>' "$OUT_DIR/02b-libByBook-region11.xml" 2>/dev/null \
    | sort -u | tr -d '<>' | tr '\n' ' ' | fold -w 100 -s >> "$REPORT"
  note ""
  note '```'
else
  note "검증용 ISBN이 없어 건너뜁니다."
fi
note ""

# ---------- 3. 도서관 코드 체계: libCode 가 도서관부호인가 ----------
# 도서관부호는 6자리 숫자입니다 (국립중앙도서관 = 011001).
# 정보나루가 자체 코드를 쓴다면 매핑 표가 하나 더 필요해집니다.

say "[3/6] libSrch — 도서관 코드 체계 확인"
note "## 3. 정보나루 libCode 와 도서관부호의 관계"
note ""
m=$(fetch "03-libSrch.xml" "$D4L_BASE/libSrch?authKey=$D4L_AUTH_KEY&pageNo=1&pageSize=5")
note "- HTTP \`${m%% *}\`, 원본: \`$OUT_DIR/03-libSrch.xml\`"
note ""
note "반환된 도서관 코드 예시:"
note ""
note '```'
sed -n 's/.*<libCode>\(<!\[CDATA\[\)\?\([^]<]*\).*/\2/p' "$OUT_DIR/03-libSrch.xml" 2>/dev/null \
  | head -5 >> "$REPORT"
note '```'
note ""
note "판단 기준: 도서관부호는 6자리 숫자입니다(국립중앙도서관 = 011001)."
note "위 값이 6자리 숫자가 아니면 **정보나루 코드와 도서관부호를 잇는 매핑 표가 추가로 필요합니다.**"
note "그 경우 library 테이블의 d4l_lib_code 컬럼이 실제로 쓰이게 됩니다."
note ""

# ---------- 4. 응답 시간: 여러 권 확인의 지연을 좌우합니다 ----------

say "[4/6] libSrchByBook 응답 시간 측정 (20회)"
note "## 4. 응답 시간 — 여러 권 동시 확인의 지연을 결정합니다"
note ""
if [ -n "$TEST_ISBN" ]; then
  : > "$OUT_DIR/04-timings.txt"
  for i in $(seq 1 20); do
    printf '\r  %d/20' "$i"
    m=$(fetch "04-timing-tmp.xml" "$D4L_BASE/libSrchByBook?authKey=$D4L_AUTH_KEY&isbn=$TEST_ISBN&region=11")
    printf '%s\n' "${m##* }" >> "$OUT_DIR/04-timings.txt"
  done
  printf '\r'
  rm -f "$OUT_DIR/04-timing-tmp.xml" "$OUT_DIR/04-timing-tmp.xml.err"

  stats=$(sort -n "$OUT_DIR/04-timings.txt" | awk '
    {a[NR]=$1; s+=$1}
    END {printf "%.3f %.3f %.3f", s/NR, a[int(NR*0.5)+1], a[int(NR*0.95)]}')
  avg=$(echo "$stats" | cut -d' ' -f1)
  p50=$(echo "$stats" | cut -d' ' -f2)
  p95=$(echo "$stats" | cut -d' ' -f3)

  note "| 지표 | 값 (초) |"
  note "|---|---|"
  note "| 평균 | $avg |"
  note "| 중앙값 | $p50 |"
  note "| 95퍼센타일 | $p95 |"
  note ""
  note "**30권 확인의 예상 소요 시간** (판본 3개, 동시 4개 호출 기준):"
  note ""
  echo "$avg" | awk '{printf "- 순차라면 약 %.0f초\n- 동시 4개면 약 %.0f초\n", $1*90, $1*90/4}' >> "$REPORT"
  note ""
  note "동시 호출을 몇 개까지 올릴지는 구현계획 8절의 호출 간격 규칙과 함께 판단합니다."
  note "이 값이 너무 크면 자주 가는 도서관만 벌크로 미리 적재하는 경로(holdings_mode)를 고려합니다."
else
  note "검증용 ISBN이 없어 건너뜁니다."
fi
note ""

# ---------- 5. 서지정보 API: 필드 목록과 구간 조회 ----------

say "[5/6] ISBN 서지정보 API — 응답 필드와 구간 조회"
note "## 5. ISBN 서지정보 API"
note ""
m=$(fetch "05-seoji.json" "$SEOJI_BASE?cert_key=$SEOJI_CERT_KEY&result_style=json&page_no=1&page_size=10&start_publish_date=20240101&end_publish_date=20240107")
note "- HTTP \`${m%% *}\`, ${m##* }초"
note "- 원본: \`$OUT_DIR/05-seoji.json\`"
note ""
note "응답 필드 이름:"
note ""
note '```'
grep -o '"[A-Za-z_][A-Za-z0-9_]*"[[:space:]]*:' "$OUT_DIR/05-seoji.json" 2>/dev/null \
  | tr -d '":' | sort -u | tr '\n' ' ' | fold -w 100 -s >> "$REPORT"
note ""
note '```'
note ""
note "확인할 것:"
note ""
note "- \`TOTAL_COUNT\` 가 있으면 구간별 건수를 미리 알 수 있어 백필 계획을 세우기 쉽습니다."
note "- 표지 이미지 URL 필드가 있는지 확인하세요. 있으면 1차에서 표지를 포기하지 않아도 됩니다."
note "- 세트 ISBN 필드(SET_ISBN 계열)가 있는지 확인하세요. bib_set_member 설계에 필요합니다."
note "- 일일 호출 한도가 문서에 명시되어 있는지 확인하고 여기에 적어 두세요."
note ""

# ---------- 6. 일일 한도 확인 ----------

say "[6/6] 정리"
note "## 6. 남은 확인 항목 (수동)"
note ""
note "- [ ] 마이페이지에서 **서버 IP를 등록**했고 1일 한도가 30,000건으로 표시되는가"
note "- [ ] 등록한 IP가 **실제 호출이 나가는 서버의 IP**와 같은가 (\`curl -s ifconfig.me\` 로 확인)"
note "- [ ] 위 2번 판정이 \"보류\"로 나왔다면 응답 원본을 직접 열어 오류 메시지를 확인"
note "- [ ] 지역 코드 목록을 확보했는가 (region=11 이 서울이 맞는지 포함)"
note ""
note "---"
note ""
note "확인이 끝나면 이 파일의 결론을 \`docs/구현계획.md\` 1절에 반영하고,"
note "특히 2번 판정에 따라 3절의 호출량 추정을 고칩니다."

say ""
say "완료. 결과: $REPORT"
say "원본 응답: $OUT_DIR/"
