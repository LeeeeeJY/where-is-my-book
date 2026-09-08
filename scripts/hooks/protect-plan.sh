#!/bin/sh
# PreToolUse (Write|Edit) — docs/구현계획.md 편집을 막습니다.
#
# 이 문서는 착수 전에 세운 계획의 기록입니다. 지금 코드와 어긋나는 대목이 생기더라도
# 고쳐서 맞추면 안 됩니다. 무엇을 예상했고 실제로는 무엇이 달랐는지가 사라지면,
# 다음 사람이 같은 자리에서 같은 판단을 되풀이하게 됩니다.
#
# 계획과 실제가 갈린 것은 docs/가정과-검증상태.md 에, 지금 지켜야 하는 제약은
# CLAUDE.md 에 적습니다. 그 둘은 일부러 막지 않았습니다. 고치라고 있는 문서입니다.
set -u
. "$(dirname "$0")/lib.sh"

path=$(tool_file_path)

case "$path" in
*"docs/구현계획.md")
    reason="docs/구현계획.md 는 착수 전에 세운 계획의 기록이라 고치지 않습니다. 계획과 달라진 사실은 docs/가정과-검증상태.md 에, 지금 지켜야 하는 제약은 CLAUDE.md 에 적으세요."
    printf '{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"deny","permissionDecisionReason":%s}}\n' \
        "$(json_string "$reason")"
    ;;
esac
exit 0
