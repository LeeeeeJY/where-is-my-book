#!/bin/sh
# PostToolUse (Write|Edit) — 이번 턴에 무엇을 고쳤는지 적어 둡니다.
#
# Stop 훅이 이 목록을 보고 Spotless 를 돌릴지, 문서를 챙기라고 알릴지 정합니다.
# git diff 로 대신할 수 없는 이유는, 턴 안에서 이미 커밋해 버리면 작업 트리가
# 깨끗해져서 무엇을 고쳤는지 알 방법이 없어지기 때문입니다.
#
# 기록은 .git 안에 두므로 커밋되지 않고, clone 마다 따로 놉니다.
set -u
. "$(dirname "$0")/lib.sh"

path=$(tool_file_path)
[ -n "$path" ] || exit 0

git_dir=$(git rev-parse --git-dir 2>/dev/null) || exit 0
printf '%s\n' "$path" >>"$git_dir/wimb-touched" 2>/dev/null
exit 0
