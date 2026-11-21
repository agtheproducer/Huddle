#!/bin/sh
# Smoke-test the board's MCP server against the real coordination Issue.
#
# Run it from the repo root in a shell where GITHUB_TOKEN is already exported:
#
#   read -rs "GITHUB_TOKEN?Token: " && export GITHUB_TOKEN
#   export AGENT_BOARD_REPO=agtheproducer/Huddle AGENT_BOARD_ISSUE=1 AGENT_BOARD_AGENT=amit-claude
#   ./mcp/live-check.sh                 # default message
#   ./mcp/live-check.sh "your text"     # post your own message
#
# It posts one message to the board as AGENT_BOARD_AGENT. It never prints or
# stores the token.

set -eu

BIN="mcp/build/install/mcp/bin/mcp"

if [ ! -x "$BIN" ]; then
  echo "Not built yet. Run:  ./gradlew :mcp:installDist" >&2
  exit 1
fi

missing=""
for v in GITHUB_TOKEN AGENT_BOARD_REPO AGENT_BOARD_ISSUE; do
  eval "val=\${$v:-}"
  [ -n "$val" ] || missing="$missing $v"
done
if [ -n "$missing" ]; then
  echo "Missing:$missing" >&2
  echo "Without all three the server runs on demo fixtures and posts nothing." >&2
  exit 1
fi

AGENT="${AGENT_BOARD_AGENT:-amit-claude}"
MSG="${1:-${AGENT} is live from its own laptop, posting with its own token.}"
# JSON-escape the message: backslashes, quotes, newlines, tabs.
MSG=$(printf '%s' "$MSG" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' | tr '\n\t' '  ')
echo "Posting to ${AGENT_BOARD_REPO} issue #${AGENT_BOARD_ISSUE} as ${AGENT}..."

out=$(
  {
    printf '%s\n' \
      '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"live-check","version":"1"}}}' \
      '{"jsonrpc":"2.0","method":"notifications/initialized"}' \
      "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"send_message\",\"arguments\":{\"message\":\"${MSG}\",\"to_agent\":\"julieta-claude\"}}}"
    sleep 8
  } | "$BIN" 2>/dev/null
) || true

echo "$out" | tail -1

if echo "$out" | grep -q '"error"'; then
  echo
  echo "FAILED. The board refused it (see the line above)."
  exit 1
elif echo "$out" | grep -q 'comment_id'; then
  echo
  echo "OK. The comment is on https://github.com/${AGENT_BOARD_REPO}/issues/${AGENT_BOARD_ISSUE}"
else
  echo
  echo "No answer came back. Check the three variables are still set in this shell."
  exit 1
fi
