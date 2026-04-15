#!/usr/bin/env bash
# Smoke-Test fuer den GraphMesh MCP-Server (Streamable-HTTP-Transport).
# Prueft, dass /mcp auf initialize + tools/list antwortet und die erwarteten
# Tools exponiert.
#
# Voraussetzung: Backend laeuft mit `spring.ai.mcp.server.protocol=STREAMABLE`.
#   `curl` und `jq` muessen installiert sein.
#
# Aufruf:  ./mcp-smoke-test.sh
# Optional:
#   MCP_URL=http://localhost:8083/mcp \
#   COLLECTION_ID=<uuid-oder-slug> \
#   QUESTION="Wie funktioniert VidiControl?" \
#   ./mcp-smoke-test.sh

set -uo pipefail

MCP_URL="${MCP_URL:-http://localhost:8083/mcp}"
COLLECTION_ID="${COLLECTION_ID:-videocontrol}"
QUESTION="${QUESTION:-Wie funktioniert VidiControl?}"
EXPECTED_TOOLS=("knowledgeQuery" "documentQuery")

PASS=0
FAIL=0

GREEN=$'\033[0;32m'
RED=$'\033[0;31m'
YELLOW=$'\033[0;33m'
RESET=$'\033[0m'

step() { echo; echo "${YELLOW}▶ $*${RESET}"; }
ok()   { echo "${GREEN}  ✓ $*${RESET}"; PASS=$((PASS + 1)); }
fail() { echo "${RED}  ✗ $*${RESET}";   FAIL=$((FAIL + 1)); }

need() {
  command -v "$1" >/dev/null 2>&1 || { echo "Missing dependency: $1"; exit 2; }
}
need curl
need jq

# Extract the JSON-RPC body from either plain JSON or an SSE `data:` frame.
extract_json() {
  local body="$1"
  if [[ "$body" == \{* ]]; then
    echo "$body"
  else
    echo "$body" | awk '/^data:/{sub(/^data:[[:space:]]*/,""); print; exit}'
  fi
}

# Usage: mcp_call <session-id-or-empty> <json-body>
# Writes headers to $HEADERS_FILE, body to stdout.
HEADERS_FILE="$(mktemp)"
trap 'rm -f "$HEADERS_FILE"' EXIT

mcp_call() {
  local session="$1"
  local body="$2"
  local args=(-sS -D "$HEADERS_FILE"
              -H 'Content-Type: application/json'
              -H 'Accept: application/json, text/event-stream'
              --max-time 15)
  [[ -n "$session" ]] && args+=(-H "Mcp-Session-Id: $session")
  curl "${args[@]}" -X POST "$MCP_URL" --data "$body"
}

status_code() {
  awk 'toupper($1) ~ /^HTTP/{code=$2} END{print code}' "$HEADERS_FILE"
}

header_value() {
  awk -v key="$1" 'BEGIN{IGNORECASE=1} tolower($1)==tolower(key)":"{sub(/\r$/,"",$2); print $2; exit}' "$HEADERS_FILE"
}

# ---------- 1) initialize ----------
step "MCP initialize"
INIT_BODY='{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"graphmesh-smoke","version":"1.0.0"}}}'
RAW=$(mcp_call "" "$INIT_BODY")
CODE=$(status_code)
if [[ "$CODE" != "200" ]]; then
  fail "initialize returned HTTP $CODE: $RAW"
  echo; echo "${RED}FAILED ($FAIL/$((PASS+FAIL)))${RESET}"; exit 1
fi
ok "HTTP 200"

SESSION=$(header_value "Mcp-Session-Id")
if [[ -n "$SESSION" ]]; then
  ok "Mcp-Session-Id: $SESSION"
else
  fail "server did not return Mcp-Session-Id header"
fi

INIT_JSON=$(extract_json "$RAW")
SERVER_NAME=$(echo "$INIT_JSON" | jq -r '.result.serverInfo.name // empty')
if [[ "$SERVER_NAME" == "graphmesh" ]]; then
  ok "serverInfo.name = graphmesh"
else
  fail "serverInfo.name was '$SERVER_NAME' (expected 'graphmesh')"
fi

# ---------- 2) notifications/initialized ----------
step "MCP notifications/initialized"
mcp_call "$SESSION" '{"jsonrpc":"2.0","method":"notifications/initialized","params":{}}' >/dev/null
CODE=$(status_code)
if [[ "$CODE" =~ ^(200|202|204)$ ]]; then
  ok "HTTP $CODE"
else
  fail "notifications/initialized returned HTTP $CODE"
fi

# ---------- 3) tools/list ----------
step "MCP tools/list"
RAW=$(mcp_call "$SESSION" '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}')
CODE=$(status_code)
if [[ "$CODE" != "200" ]]; then
  fail "tools/list returned HTTP $CODE: $RAW"
else
  ok "HTTP 200"
  TOOLS_JSON=$(extract_json "$RAW")
  TOOL_NAMES=$(echo "$TOOLS_JSON" | jq -r '.result.tools[].name' 2>/dev/null)
  echo "  Tools: $(echo "$TOOL_NAMES" | paste -sd, -)"
  for expected in "${EXPECTED_TOOLS[@]}"; do
    if grep -Fxq "$expected" <<<"$TOOL_NAMES"; then
      ok "tool '$expected' present"
    else
      fail "tool '$expected' missing"
    fi
  done
fi

# ---------- 4) tools/call knowledgeQuery ----------
call_tool() {
  local tool="$1"
  local id="$2"
  local args_json
  args_json=$(jq -nc --arg q "$QUESTION" --arg c "$COLLECTION_ID" \
    '{question:$q, collectionId:$c}')
  local body
  body=$(jq -nc --arg t "$tool" --argjson args "$args_json" --argjson id "$id" \
    '{jsonrpc:"2.0", id:$id, method:"tools/call", params:{name:$t, arguments:$args}}')
  mcp_call "$SESSION" "$body"
}

check_tool_result() {
  local tool="$1"
  local raw="$2"
  local code
  code=$(status_code)
  if [[ "$code" != "200" ]]; then
    fail "$tool returned HTTP $code"
    return
  fi
  ok "HTTP 200"
  local json
  json=$(extract_json "$raw")
  local err
  err=$(echo "$json" | jq -r '.error.message // empty')
  if [[ -n "$err" ]]; then
    fail "$tool JSON-RPC error: $err"
    return
  fi
  local is_error text_len
  is_error=$(echo "$json" | jq -r '.result.isError // false')
  text_len=$(echo "$json" | jq -r '[.result.content[]? | select(.type=="text") | .text] | join("") | length')
  if [[ "$is_error" == "true" ]]; then
    local text
    text=$(echo "$json" | jq -r '[.result.content[]? | select(.type=="text") | .text] | join("")')
    fail "$tool reported isError: ${text:0:200}"
    return
  fi
  if (( text_len < 1 )); then
    fail "$tool returned empty content"
    return
  fi
  ok "$tool content length: $text_len chars"
  local preview
  preview=$(echo "$json" | jq -r '[.result.content[]? | select(.type=="text") | .text] | join("") | .[0:160]')
  echo "    preview: ${preview}..."
}

step "MCP tools/call knowledgeQuery (collection=$COLLECTION_ID)"
RAW=$(call_tool "knowledgeQuery" 3)
check_tool_result "knowledgeQuery" "$RAW"

# ---------- 5) tools/call documentQuery ----------
step "MCP tools/call documentQuery (collection=$COLLECTION_ID)"
RAW=$(call_tool "documentQuery" 4)
check_tool_result "documentQuery" "$RAW"

# ---------- Summary ----------
echo
if (( FAIL == 0 )); then
  echo "${GREEN}ALL PASSED ($PASS)${RESET}"
  exit 0
else
  echo "${RED}FAILED: $FAIL failed, $PASS passed${RESET}"
  exit 1
fi
