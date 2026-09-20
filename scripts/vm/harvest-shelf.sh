#!/usr/bin/env bash
#
# 도서관 장서를 받아 서가 파일로 적습니다. **VM 에서 돌리세요.**
#
# 정보나루 한도는 등록한 IP 에서 나갈 때만 하루 30,000건이고 아니면 500건입니다.
# 수집은 도서관 한 곳에 수천 번을 부르므로 등록된 IP 가 아니면 시작하자마자 막힙니다.
# 같은 이유로 GitHub Actions 에서 돌리면 안 됩니다. 러너 IP 는 고정되지 않습니다.
#
#   ./harvest-shelf.sh 141321
#   ./harvest-shelf.sh 141321,141053
#
# 서비스하는 컨테이너는 건드리지 않습니다. 수집은 따로 뜬 컨테이너가 하고, 다 만든
# 뒤에 한 번에 갈아 끼우므로 그동안에도 서가는 예전 것으로 답합니다.
set -euo pipefail

LIBS="${1:-}"
if [ -z "$LIBS" ]; then
    echo "받을 도서관부호를 적으세요. 예: $0 141321" >&2
    exit 2
fi

IMAGE="${WIMB_IMAGE:-ghcr.io/leeeeejy/where-is-my-book:latest}"
CACHE_VOLUME="${WIMB_CACHE_VOLUME:-wimb-data}"
ENV_FILE="${WIMB_ENV_FILE:-/etc/wimb/wimb.env}"

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
