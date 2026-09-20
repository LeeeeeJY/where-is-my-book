#!/bin/bash
#
# 서버 설정을 고치고 **실제로 반영되는 데까지** 합니다.
#
#   ./set-env.sh WIMB_CORS_ALLOWED_ORIGINS=https://a.vercel.app,https://a-*.vercel.app
#   ./set-env.sh D4L_AUTH_KEY=발급받은키
#   ./set-env.sh --show
#
# **두 단계가 따로 조용히 지나갑니다.** 환경 변수는 컨테이너가 뜰 때 한 번 읽으므로
# `~/wimb.env` 를 고쳐도 돌고 있는 서버는 예전 값 그대로이고, deploy.sh 는 이미지가
# 그대로면 「새 이미지가 없습니다」 한 줄만 남기고 끝납니다. 둘 다 오류를 내지 않아서
# **고친 사람은 반영된 줄 압니다.** 그래서 여기서는 고치는 것과 다시 띄우는 것을 한
# 묶음으로 두고, 뜬 것까지 확인합니다.
#
# **값을 화면에 찍지 않습니다.** 이 파일에는 정보나루 인증키가 함께 들어 있어서, 확인하려고
# 통째로 찍는 습관이 들면 그 화면이 갈무리되어 돌아다닙니다. --show 는 키 이름만 냅니다.
set -euo pipefail

ENV_FILE="${WIMB_ENV_FILE:-$HOME/wimb.env}"
HERE="$(cd "$(dirname "$0")" && pwd)"
RESTART=1
declare -a PAIRS=()

for arg in "$@"; do
    case "$arg" in
        --show)
            if [ ! -f "$ENV_FILE" ]; then
                echo "$ENV_FILE 이 없습니다." >&2
                exit 1
            fi
            echo "$ENV_FILE 에 들어 있는 설정(값은 적지 않습니다):"
            # 주석과 빈 줄을 빼고 등호 앞만 냅니다.
            grep -v '^[[:space:]]*#' "$ENV_FILE" | grep '=' | sed 's/=.*//' | sed 's/^/  /'
            exit 0
            ;;
        # 여러 개를 한꺼번에 고칠 때 쓰세요. 다시 띄우는 것은 마지막에 한 번뿐입니다.
        --no-restart) RESTART=0 ;;
        -*)
            echo "모르는 인자입니다: $arg" >&2
            exit 2
            ;;
        *=*) PAIRS+=("$arg") ;;
        *)
            echo "KEY=VALUE 로 적어 주세요. 받은 값: $arg" >&2
            exit 2
            ;;
    esac
done

if [ "${#PAIRS[@]}" -eq 0 ]; then
    echo "고칠 설정을 적어 주세요. 예: $0 WIMB_CORS_ALLOWED_ORIGINS=https://..." >&2
    echo "지금 무엇이 들어 있는지는 $0 --show 로 봅니다." >&2
    exit 2
fi

if [ ! -f "$ENV_FILE" ]; then
    echo "$ENV_FILE 이 없습니다. 본보기를 복사해서 시작하세요:" >&2
    echo "  cp $HERE/wimb.env.example $ENV_FILE && chmod 600 $ENV_FILE" >&2
    exit 1
fi

# 한 키를 고쳐 쓰고, 없으면 끝에 붙입니다. 같은 키가 여러 줄 있으면 하나만 남깁니다.
# **sed 를 쓰지 않는 것이 의도적입니다.** 값에 / 와 & 가 흔히 들어가는데(주소가 그렇습니다)
# 그것들이 sed 에서 각각 구분자와 「맞은 부분 전체」로 읽혀 값이 조용히 망가집니다.
apply() {
    local key="$1" value="$2" tmp found=0 line
    tmp=$(mktemp "${ENV_FILE}.XXXXXX")
    # 권한을 옮기기 전에 맞춥니다. 인증키가 든 파일이라 잠깐이라도 남에게 읽히면 안 됩니다.
    chmod 600 "$tmp"
    while IFS= read -r line || [ -n "$line" ]; do
        if [[ "$line" == "$key="* ]]; then
            if [ "$found" -eq 0 ]; then
                printf '%s=%s\n' "$key" "$value" >> "$tmp"
                found=1
            fi
            continue
        fi
        printf '%s\n' "$line" >> "$tmp"
    done < "$ENV_FILE"
    [ "$found" -eq 1 ] || printf '%s=%s\n' "$key" "$value" >> "$tmp"
    mv "$tmp" "$ENV_FILE"
}

changed=0
for pair in "${PAIRS[@]}"; do
    key="${pair%%=*}"
    value="${pair#*=}"

    # 환경 변수 이름의 규칙입니다. 오타를 여기서 잡지 않으면 서버는 그 줄을 그냥
    # 무시하고 기본값으로 돌아, 「설정했는데 안 먹는다」가 됩니다.
    if ! [[ "$key" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]]; then
        echo "환경 변수 이름이 아닙니다: $key" >&2
        exit 2
    fi

    # 따옴표로 감싼 값을 그대로 받으면 따옴표까지 값이 됩니다(--env-file 은 셸이
    # 아닙니다). 셸이 이미 벗겼을 수도 있으므로 여기서는 남아 있을 때만 알립니다.
    if [[ "$value" == \"*\" || "$value" == \'*\' ]]; then
        echo "값을 따옴표로 감싸지 마세요. --env-file 은 따옴표를 값의 일부로 읽습니다." >&2
        exit 2
    fi

    before=$(grep -c "^$key=" "$ENV_FILE" || true)
    if grep -qxF "$key=$value" "$ENV_FILE"; then
        echo "이미 같은 값입니다: $key"
    elif [ "$before" -gt 0 ]; then
        echo "바꿨습니다: $key"
        changed=1
    else
        echo "새로 넣었습니다: $key"
        changed=1
    fi
    apply "$key" "$value"
done

if [ "$RESTART" -eq 0 ]; then
    echo
    echo "다시 띄우지 않았습니다. **아직 서버에 반영되지 않았습니다.** 반영하려면:"
    echo "  $HERE/deploy.sh --force"
    exit 0
fi

# **값이 그대로여도 다시 띄웁니다.** 파일이 이미 맞는데 서버만 예전 값으로 도는 경우가
# 바로 이 스크립트가 있는 이유라, 「파일이 안 바뀌었으니 건너뛴다」로 두면 그 경우를
# 영영 못 고칩니다. 다시 띄우는 데 몇 초면 되고, 건너뛰어서 잃는 쪽이 훨씬 비쌉니다.
[ "$changed" -eq 1 ] || echo "파일은 그대로입니다. 돌고 있는 서버에 반영되었는지 확인하려고 다시 띄웁니다."
echo
"$HERE/deploy.sh" --force

# 컨테이너가 이 파일을 실제로 읽었는지 봅니다. **이름만 봅니다.** 값을 견주려면 값을
# 꺼내야 하는데, 그 안에 인증키가 있습니다.
echo
for pair in "${PAIRS[@]}"; do
    key="${pair%%=*}"
    if docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' wimb-api 2>/dev/null \
        | sed 's/=.*//' | grep -qx "$key"; then
        echo "돌고 있는 컨테이너가 $key 를 들고 있습니다."
    else
        echo "!! 돌고 있는 컨테이너에 $key 가 없습니다. $ENV_FILE 을 읽었는지 보세요." >&2
    fi
done
