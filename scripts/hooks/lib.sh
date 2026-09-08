# 훅 스크립트가 함께 쓰는 도우미입니다. 직접 실행하지 않고 . 으로 읽어 들입니다.
#
# 훅은 표준입력으로 JSON 을 받습니다. jq 가 없는 기계에서도 동작해야 하므로
# jq → node → sed 순으로 물러섭니다. sed 는 \uXXXX 를 되돌리지 못해 한글이 섞인
# 경로에서 빗나갈 수 있는데, node 는 프론트엔드를 돌리는 이상 항상 있습니다.

tool_field() {  # 표준입력의 훅 페이로드에서 tool_input.<필드> 를 꺼냅니다
    field=$1
    payload=$(cat)
    if command -v jq >/dev/null 2>&1; then
        printf '%s' "$payload" | jq -r --arg f "$field" '.tool_input[$f] // ""' 2>/dev/null && return 0
    fi
    if command -v node >/dev/null 2>&1; then
        printf '%s' "$payload" | WIMB_FIELD="$field" node -e '
            let s = "";
            process.stdin.on("data", d => s += d).on("end", () => {
                try { process.stdout.write(String(JSON.parse(s).tool_input?.[process.env.WIMB_FIELD] ?? "")); }
                catch { /* 형식이 다르면 아무것도 내보내지 않습니다 */ }
            });
        ' 2>/dev/null && return 0
    fi
    printf '%s' "$payload" |
        sed -n "s/.*\"$field\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\\1/p"
}

json_string() {  # json_string <문자열>  → JSON 문자열 리터럴
    if command -v jq >/dev/null 2>&1; then
        printf '%s' "$1" | jq -Rs . && return 0
    fi
    if command -v node >/dev/null 2>&1; then
        printf '%s' "$1" | node -e '
            let s = "";
            process.stdin.on("data", d => s += d)
                .on("end", () => process.stdout.write(JSON.stringify(s)));
        ' && return 0
    fi
    printf '"%s"' "$(printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g')"
}
