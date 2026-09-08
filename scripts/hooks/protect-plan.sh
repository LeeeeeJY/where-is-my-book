#!/bin/sh
# PreToolUse (Write|Edit|Bash) — docs/구현계획.md 를 고치지 못하게 막습니다.
#
# 이 문서는 착수 전에 세운 계획의 기록입니다. 지금 코드와 어긋나는 대목이 생기더라도
# 고쳐서 맞추면 안 됩니다. 무엇을 예상했고 실제로는 무엇이 달랐는지가 사라지면,
# 다음 사람이 같은 자리에서 같은 판단을 되풀이하게 됩니다.
#
# 계획과 실제가 갈린 것은 docs/가정과-검증상태.md 에, 지금 지켜야 하는 제약은
# CLAUDE.md 에 적습니다. 그 둘은 일부러 막지 않았습니다. 고치라고 있는 문서입니다.
#
# **Bash 까지 보는 것이 중요합니다.** Write/Edit 만 막으면 heredoc 이나 sed -i 로
# 그냥 지나갑니다. 실제로 이 저장소의 문서는 대부분 Bash 로 고쳐졌습니다.
#
# 다만 「구현계획 이 나오고 어딘가에 쓰기가 있으면 막는다」로는 안 됩니다. 이 문서를
# **인용하는** 다른 문서를 고칠 때마다 걸립니다(README 의 훅 설명이 실제로 그랬습니다).
# 그래서 경로가 **쓰기의 대상 자리**에 있을 때만 막습니다. 읽기는 막지 않습니다.
#
# 명령 문자열을 보는 방식이라 완전하지는 않습니다. 경로를 변수에 담거나 docs/*.md 로
# 뭉뚱그리면 놓칩니다. 실수로 고치는 것을 막는 장치이지, 우회를 막는 장치가 아닙니다.
set -u
. "$(dirname "$0")/lib.sh"

payload=$(cat)
path=$(printf '%s' "$payload" | tool_field file_path)
command_line=$(printf '%s' "$payload" | tool_field command)

deny() {
    reason="docs/구현계획.md 는 착수 전에 세운 계획의 기록이라 고치지 않습니다. 계획과 달라진 사실은 docs/가정과-검증상태.md 에, 지금 지켜야 하는 제약은 CLAUDE.md 에 적으세요. 읽는 것은 막지 않습니다."
    printf '{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"deny","permissionDecisionReason":%s}}\n' \
        "$(json_string "$reason")"
    exit 0
}

# 1. Write / Edit 이 그 파일을 겨냥한 경우
case "$path" in
*"docs/구현계획.md") deny ;;
esac

# 2. Bash 명령이 그 파일을 쓰기 대상으로 삼은 경우.
#    grep 은 줄 단위로 보므로 [^;&|]* 가 「같은 명령 안에서」를 뜻하게 됩니다.
[ -n "$command_line" ] || exit 0

# 2-1. 리다이렉트의 목적지
if printf '%s' "$command_line" | grep -qE '>[[:space:]]*[^[:space:]|]*구현계획'; then
    deny
fi
# 2-2. 제자리 편집 (sed -i, perl -i)
if printf '%s' "$command_line" | grep -qE '[[:space:]]-[a-zA-Z]*i([[:space:]]|$)[^;&|]*구현계획'; then
    deny
fi
# 2-3. 파일을 인자로 받아 지우거나 옮기거나 덮어쓰는 명령
if printf '%s' "$command_line" | grep -qE '(^|[[:space:];&|(])(mv|rm|cp|tee|truncate|patch|shred|install)[[:space:]][^;&|]*구현계획'; then
    deny
fi
# 2-4. 스크립트 안에서 그 경로를 열어 쓰는 경우.
#      Path("docs/구현계획.md") 는 걸리고, 본문에 그 이름이 인용만 된 것은 걸리지 않습니다.
if printf '%s' "$command_line" | grep -qE '(Path|open|writeFileSync|writeFile|appendFile|unlink)\([^)]*구현계획'; then
    deny
fi
exit 0
