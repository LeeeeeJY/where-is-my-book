#!/bin/bash
#
# 새 이미지가 올라와 있으면 받아서 다시 띄웁니다. 없으면 아무것도 하지 않습니다.
#
# **이미지는 GitHub Actions 가 만듭니다.** 무료 등급 VM 에서 자바를 컴파일하면 10~15분이
# 걸리는데(기본 CPU 가 코어의 0.25개, 디스크 쓰기 45 IOPS), 러너에서는 2~3분입니다.
# 여기서는 받아서 갈아 끼우기만 하므로 1분 안쪽입니다. 절차는 README.md 를 보세요.
#
# **받기에 실패하면 돌던 서버를 건드리지 않습니다.** 먼저 받고 성공했을 때만 컨테이너를
# 바꿉니다. 반대 순서로 하면 레지스트리가 잠깐 흔들린 날 사이트가 통째로 내려갑니다.
#
# 직접 실행해도 되고, wimb-deploy.timer 가 주기적으로 부르게 해도 됩니다.
set -euo pipefail

# 이 스크립트가 저장소 안(scripts/vm/)에 있으므로 위치에서 저장소를 찾습니다.
# GitHub 에서 이름이 바뀌어 clone 한 디렉터리가 where-is-my-book 일 수도
# where-is-my-books 일 수도 있는데, 하드코딩하면 그 차이로 조용히 실패합니다.
REPO="${WIMB_REPO:-$(cd "$(dirname "$0")/../.." && pwd)}"
BRANCH="${WIMB_BRANCH:-claude/library-search-planning-6qkxh1}"
ENV_FILE="${WIMB_ENV_FILE:-$HOME/wimb.env}"
IMAGE="${WIMB_IMAGE:-ghcr.io/leeeeejy/where-is-my-book:latest}"

# 본문을 함수로 감싼 것이 의도적입니다. 아래에서 git 이 이 파일 자체를 갈아 끼우는데,
# bash 는 스크립트를 조금씩 읽어 가며 실행하므로 도중에 내용이 바뀌면 엉뚱한 줄을
# 실행하게 됩니다. 함수는 부르기 전에 통째로 읽히므로 그 일이 생기지 않습니다.
main() {
    cd "$REPO"

    # 저장소도 최신으로 둡니다. 이 스크립트와 systemd 유닛이 여기 들어 있습니다.
    # 실패해도 배포는 계속합니다. 이미지만 있으면 띄울 수 있습니다.
    if git fetch --quiet origin "$BRANCH" 2>/dev/null; then
        git merge --ff-only --quiet "origin/$BRANCH" 2>/dev/null || true
    fi

    echo "이미지를 확인합니다: $IMAGE"
    docker pull --quiet "$IMAGE" >/dev/null

    latest=$(docker image inspect -f '{{.Id}}' "$IMAGE")
    running=$(docker inspect -f '{{.Image}}' wimb-api 2>/dev/null || echo none)

    # **돌고 있는 컨테이너와 비교합니다.** 받은 이미지가 그대로인지만 보면, 컨테이너가
    # 죽어 있거나 예전 이미지로 떠 있을 때 그것을 영영 고치지 못합니다.
    if [ "$running" = "$latest" ]; then
        echo "새 이미지가 없습니다 (${latest#sha256:})"
        exit 0
    fi

    echo "컨테이너를 바꿉니다: ${running#sha256:} → ${latest#sha256:}"
    docker rm -f wimb-api >/dev/null 2>&1 || true
    docker run -d --name wimb-api --restart unless-stopped \
        -p 127.0.0.1:8080:8080 --env-file "$ENV_FILE" "$IMAGE" >/dev/null

    # 떴는지 확인합니다. 「배포했다」와 「실제로 뜬다」는 다릅니다.
    echo "뜨기를 기다립니다."
    for _ in $(seq 1 60); do
        if status=$(curl -fsS localhost:8080/api/status 2>/dev/null); then
            echo "떴습니다: $status"
            # **어느 코드가 뜬 것인지 기록에 남깁니다.** 이것이 없으면 화면이 예전
            # 그대로일 때 「고치다 만 것」인지 「배포가 안 된 것」인지 구별되지 않아,
            # 없는 버그를 찾게 됩니다. 실제로 빌드 셋이 겹쳐 오래된 이미지가 새 이미지를
            # 덮어쓴 적이 있는데, 그때 하루치를 추측으로 보냈습니다.
            echo "돌고 있는 코드: $(curl -fsS localhost:8080/api/version 2>/dev/null || echo '(모름)')"
            # 오래된 이미지를 정리합니다. 30GB 디스크가 금방 찹니다.
            docker image prune -f >/dev/null 2>&1 || true
            exit 0
        fi
        sleep 5
    done

    echo "5분 안에 뜨지 않았습니다. docker logs wimb-api 를 보세요." >&2
    exit 1
}

main "$@"
