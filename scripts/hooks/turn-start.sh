#!/bin/sh
# UserPromptSubmit — 턴이 시작할 때의 커밋을 적어 둡니다.
#
# Stop 훅이 이것과 지금을 비교해서 이번 턴에 무엇이 바뀌었는지 알아냅니다.
#
# **고친 파일을 PostToolUse 로 세지 않는 이유가 있습니다.** 그러면 Write/Edit 로 고친
# 것만 세어지는데, 실제로는 heredoc 이나 sed 로 고치는 경우가 더 많습니다.
# 커밋을 기준으로 보면 어떤 방법으로 고쳤든 똑같이 잡힙니다.
set -u

git_dir=$(git rev-parse --git-dir 2>/dev/null) || exit 0
git rev-parse HEAD >"$git_dir/wimb-turn-start" 2>/dev/null || true
exit 0
