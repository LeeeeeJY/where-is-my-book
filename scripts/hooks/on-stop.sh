#!/bin/sh
# Stop — 이번 턴에 바뀐 것을 보고 두 가지를 합니다.
#
#   1. 자바가 바뀌었으면 Spotless 를 돌립니다.
#   2. 소스는 바뀌었는데 문서가 그대로면 알리기만 합니다. 막지는 않습니다.
#
# 「바뀐 것」은 턴 시작 시점의 커밋과 지금의 차이에, 아직 커밋하지 않은 것을 더한
# 목록입니다. 턴 안에서 이미 커밋해 버려도 잡히고, Bash 로 고쳐도 잡힙니다.
# 아무것도 바뀌지 않은 턴에서는 Gradle 이 뜨지 않습니다.
set -u
. "$(dirname "$0")/lib.sh"

git_dir=$(git rev-parse --git-dir 2>/dev/null) || exit 0
root=$(git rev-parse --show-toplevel 2>/dev/null) || exit 0

committed=""
start_file="$git_dir/wimb-turn-start"
if [ -f "$start_file" ]; then
    start=$(cat "$start_file" 2>/dev/null)
    rm -f "$start_file"
    if [ -n "$start" ] && git -C "$root" cat-file -e "$start^{commit}" 2>/dev/null; then
        committed=$(git -C "$root" diff --name-only "$start" HEAD 2>/dev/null)
    fi
fi

# 아직 커밋하지 않은 것도 함께 봅니다. 이름을 바꾼 것은 새 이름만 남깁니다.
dirty=$(git -C "$root" status --porcelain 2>/dev/null | cut -c4- | sed 's/.* -> //')

files=$(printf '%s\n%s\n' "$committed" "$dirty" | grep -v '^[[:space:]]*$' | sort -u)
[ -n "$files" ] || exit 0

message=""
add_message() {
    if [ -n "$message" ]; then message="$message $1"; else message="$1"; fi
}

# 1. Spotless
if printf '%s\n' "$files" | grep -q '\.java$' && [ -x "$root/backend/gradlew" ]; then
    digest() { find "$root/backend/src" -name '*.java' -type f -exec cat {} + 2>/dev/null | cksum; }
    before=$(digest)
    (cd "$root/backend" && ./gradlew --quiet spotlessApply) >/dev/null 2>&1
    after=$(digest)
    if [ "$before" != "$after" ]; then
        add_message "Spotless 가 자바 소스를 고쳤습니다. 이미 커밋했다면 그 변경은 커밋 밖에 남아 있으니 git diff 로 확인하세요."
    fi
fi

# 2. 문서 챙기기 알림
source_changed=$(printf '%s\n' "$files" | grep -E '^(backend/src/main/|frontend/src/)' | head -n 1)
doc_changed=$(printf '%s\n' "$files" | grep -E '\.md$' | head -n 1)
if [ -n "$source_changed" ] && [ -z "$doc_changed" ]; then
    add_message "소스는 바뀌었는데 문서는 그대로입니다. CLAUDE.md 의 제약이나 README.md 의 「무엇이 되는지」가 달라졌는지 확인하세요."
fi

[ -n "$message" ] || exit 0
printf '{"systemMessage":%s}\n' "$(json_string "$message")"
exit 0
