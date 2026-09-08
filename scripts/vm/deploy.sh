#!/bin/bash
#
# 새 코드가 올라와 있으면 받아서 다시 띄웁니다. 없으면 아무것도 하지 않습니다.
#
# **빌드가 실패하면 돌던 서버를 건드리지 않습니다.** 먼저 이미지를 만들고 성공했을 때만
# 컨테이너를 바꿉니다. 반대 순서로 하면 빌드가 깨진 날 사이트가 통째로 내려갑니다.
#
# 직접 실행해도 되고, wimb-deploy.timer 가 주기적으로 부르게 해도 됩니다.
set -euo pipefail

# 이 스크립트가 저장소 안(scripts/vm/)에 있으므로 위치에서 저장소를 찾습니다.
# GitHub 에서 이름이 바뀌어 clone 한 디렉터리가 where-is-my-book 일 수도
# where-is-my-books 일 수도 있는데, 하드코딩하면 그 차이로 조용히 실패합니다.
REPO="${WIMB_REPO:-$(cd "$(dirname "$0")/../.." && pwd)}"
BRANCH="${WIMB_BRANCH:-claude/library-search-planning-6qkxh1}"
ENV_FILE="${WIMB_ENV_FILE:-$HOME/wimb.env}"

cd "$REPO"
git fetch --quiet origin "$BRANCH"

local_rev=$(git rev-parse HEAD)
remote_rev=$(git rev-parse "origin/$BRANCH")
if [ "$local_rev" = "$remote_rev" ]; then
    echo "새 코드가 없습니다 (${local_rev:0:7})"
    exit 0
fi

echo "새 코드를 받습니다: ${local_rev:0:7} → ${remote_rev:0:7}"
git merge --ff-only "origin/$BRANCH"

echo "이미지를 만듭니다. e2-micro 에서 10분쯤 걸립니다."
cd backend
docker build -t wimb-api:latest .

echo "컨테이너를 바꿉니다."
docker rm -f wimb-api >/dev/null 2>&1 || true
docker run -d --name wimb-api --restart unless-stopped \
    -p 127.0.0.1:8080:8080 --env-file "$ENV_FILE" wimb-api:latest >/dev/null

# 떴는지 확인합니다. 「배포했다」와 「실제로 뜬다」는 다릅니다.
echo "뜨기를 기다립니다."
for _ in $(seq 1 60); do
    if status=$(curl -fsS localhost:8080/api/status 2>/dev/null); then
        echo "떴습니다: $status"
        # 오래된 이미지를 정리합니다. 30GB 디스크가 금방 찹니다.
        docker image prune -f >/dev/null 2>&1 || true
        exit 0
    fi
    sleep 5
done

echo "5분 안에 뜨지 않았습니다. docker logs wimb-api 를 보세요." >&2
exit 1
