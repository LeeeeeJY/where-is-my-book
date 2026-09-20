#!/usr/bin/env bash
#
# 도서관 장서를 받아 서가 파일로 적습니다. **VM 에서 돌리세요.**
#
# 정보나루 한도는 등록한 IP 에서 나갈 때만 하루 30,000건이고 아니면 500건입니다.
# 수집은 도서관 한 곳에 수천 번을 부르므로 등록된 IP 가 아니면 시작하자마자 막힙니다.
# 같은 이유로 GitHub Actions 에서 돌리면 안 됩니다. 러너 IP 는 고정되지 않습니다.
#
# **도서관부호와 대주제를 함께 적습니다.** 서가의 단위가 (도서관 × 대주제)라 도서관만
# 적으면 서버가 무엇을 세울지 알 수 없어 그 자리에서 거절합니다. 대주제는 KDC 첫 자리로
# 0 총류, 1 철학, 2 종교, 3 사회과학, 4 자연과학, 5 기술과학, 6 예술, 7 언어, 8 문학,
# 9 역사입니다.
#
#   ./harvest-shelf.sh 141321:8
#   ./harvest-shelf.sh 141321:8,141321:9,141053:8
#
# 서비스하는 컨테이너는 건드리지 않습니다. 수집은 따로 뜬 컨테이너가 하고, 다 만든
# 뒤에 한 번에 갈아 끼우므로 그동안에도 서가는 예전 것으로 답합니다.
set -euo pipefail

LIBS="${1:-}"
if [ -z "$LIBS" ]; then
    echo "받을 서가를 「도서관부호:대주제」로 적으세요. 예: $0 141321:8" >&2
    exit 2
fi

# 도서관부호만 적은 것을 여기서 잡습니다. 그대로 넘기면 수백 회를 부른 뒤가 아니라
# 시작하자마자 거절당하기는 하지만, 오류가 컨테이너 로그에만 남아 「아무 일도 안
# 일어났다」로 보입니다.
if ! printf '%s' "$LIBS" | grep -Eq '^[0-9]+:[0-9](,[0-9]+:[0-9])*$'; then
    echo "「도서관부호:대주제」를 쉼표로 이어 적으세요(대주제는 0~9). 받은 값: $LIBS" >&2
    exit 2
fi

IMAGE="${WIMB_IMAGE:-ghcr.io/leeeeejy/where-is-my-book:latest}"
CACHE_VOLUME="${WIMB_CACHE_VOLUME:-wimb-data}"
ENV_FILE="${WIMB_ENV_FILE:-$HOME/wimb.env}"

if [ ! -f "$ENV_FILE" ]; then
    echo "$ENV_FILE 이 없습니다. 인증키가 여기 있어야 수집이 시작됩니다." >&2
    exit 1
fi

# **나가는 IP 를 눈으로 확인하는 것까지가 한 묶음입니다.** 등록한 것과 다르면 하루
# 500건으로 떨어지고, 수집은 그 자리에서 멈춥니다. 증상은 「갑자기 안 된다」입니다.
echo "나가는 IP: $(curl -s --max-time 10 https://api.ipify.org || echo '확인 실패')"
echo "정보나루에 등록한 주소와 같은지 확인하세요."
echo

# 수집은 십 분 넘게 걸립니다. 붙어 있지 않아도 되도록 로그를 파일로도 남깁니다.
LOG="/tmp/wimb-harvest-$(date +%Y%m%d-%H%M%S).log"
echo "도서관 $LIBS 수집을 시작합니다. 로그: $LOG"

docker run --rm \
    --env-file "$ENV_FILE" \
    -e WIMB_SHELF_HARVEST="$LIBS" \
    -v "$CACHE_VOLUME":/data \
    "$IMAGE" 2>&1 | tee "$LOG"

echo
echo "끝났습니다. 서비스 중인 서버가 새 서가를 보는지 확인하세요:"
echo "  curl -s http://127.0.0.1:8080/api/status | grep shelfLibraries"
