#!/usr/bin/env bash
# Live-Monitor der Extraction-Consumer-Groups auf graphmesh.chunk.created.
#
# Usage:
#   ./scripts/extraction-monitor.sh                    # loop, 5s interval
#   ./scripts/extraction-monitor.sh --once             # einmal
#   INTERVAL=2 ./scripts/extraction-monitor.sh
#   KAFKA_CONTAINER=graphmesh-kafka-1 ./scripts/extraction-monitor.sh

set -u

CONTAINER="${KAFKA_CONTAINER:-graphmesh-kafka-1}"
BOOTSTRAP="${KAFKA_BOOTSTRAP:-localhost:9092}"
INTERVAL="${INTERVAL:-5}"
ONCE=0
[ "${1:-}" = "--once" ] && ONCE=1

CG_LIST="
graphmesh-definition-extractor
graphmesh-topic-extractor
graphmesh-structured-extractor
graphmesh-relationship-extractor
graphmesh-agent-extractor
graphmesh-ontology-extractor
graphmesh-embedding
"

if [ -t 1 ]; then
  RST=$'\033[0m'; BLD=$'\033[1m'; DIM=$'\033[2m'
  GRN=$'\033[32m'; YLW=$'\033[33m'; RED=$'\033[31m'; CYN=$'\033[36m'
else
  RST=""; BLD=""; DIM=""; GRN=""; YLW=""; RED=""; CYN=""
fi

describe_one() {
  # Gibt Zeilen im Format: "PARTITION CUR LEO LAG CONSUMER"
  local group="$1"
  docker exec "$CONTAINER" kafka-consumer-groups \
      --bootstrap-server "$BOOTSTRAP" --describe --group "$group" 2>/dev/null \
    | tr -d '\r' \
    | awk -v g="$group" '
        $1 == g { print $3, $4, $5, $6, ($7 == "" ? "-" : $7) }
      '
}

render_once() {
  printf '\033[H\033[2J'
  printf "%s%sGraphMesh Extraction Monitor%s   %s   (container=%s)\n\n" \
    "$BLD" "$CYN" "$RST" "$(date '+%Y-%m-%d %H:%M:%S')" "$CONTAINER"
  printf "%s%-34s %8s %10s %10s %10s %8s%s\n" \
    "$DIM" "CONSUMER GROUP" "PARTS" "CUR" "END" "LAG" "ACTIVE" "$RST"
  printf "%s" "$DIM"
  printf -- '-%.0s' $(seq 1 90); printf "%s\n" "$RST"

  grand_lag=0
  grand_active=0
  group_total=0

  echo "$CG_LIST" | while read -r group; do
    [ -z "$group" ] && continue
    group_total=$((group_total + 1))

    lines="$(describe_one "$group")"
    if [ -z "$lines" ]; then
      printf "%-34s %s(no data)%s\n" "$group" "$DIM" "$RST"
      continue
    fi

    sum_cur=0; sum_leo=0; sum_lag=0; parts=0; active=0
    # Verwende here-string fuer konsistente Variablen-Scopes
    while read -r _p cur leo lag consumer; do
      [ "$cur" = "-" ] && cur=0
      [ "$leo" = "-" ] && leo=0
      [ "$lag" = "-" ] && lag=0
      case "$cur" in ''|*[!0-9]*) cur=0 ;; esac
      case "$leo" in ''|*[!0-9]*) leo=0 ;; esac
      case "$lag" in ''|*[!0-9]*) lag=0 ;; esac
      sum_cur=$((sum_cur + cur))
      sum_leo=$((sum_leo + leo))
      sum_lag=$((sum_lag + lag))
      parts=$((parts + 1))
      [ -n "$consumer" ] && [ "$consumer" != "-" ] && active=1
    done <<EOF
$lines
EOF

    if [ "$sum_lag" -gt 1000 ]; then lag_c="$RED"
    elif [ "$sum_lag" -gt 0 ];  then lag_c="$YLW"
    else lag_c="$GRN"
    fi

    if [ "$active" -eq 1 ]; then
      act="${GRN}yes${RST}"
    else
      act="${RED}NO${RST}"
    fi

    printf "%-34s %8d %10d %10d ${lag_c}%10d${RST} %15b\n" \
      "$group" "$parts" "$sum_cur" "$sum_leo" "$sum_lag" "$act"
  done

  # Summen noch einmal separat (die while-loop laeuft in Subshell wg. pipe,
  # daher kein Grand-Total-Carry moeglich ohne extra Aufwand).
  printf "%s" "$DIM"
  printf -- '-%.0s' $(seq 1 90); printf "%s\n" "$RST"

  total_lag=0
  active_count=0
  group_count=0
  for group in $CG_LIST; do
    [ -z "$group" ] && continue
    group_count=$((group_count + 1))
    lines="$(describe_one "$group")"
    [ -z "$lines" ] && continue
    while read -r _p _c _l lag consumer; do
      case "$lag" in ''|*[!0-9]*) lag=0 ;; esac
      total_lag=$((total_lag + lag))
      [ -n "$consumer" ] && [ "$consumer" != "-" ] && active_count=$((active_count + 1)) && break
    done <<EOF
$lines
EOF
  done

  printf "%sTotal lag:%s %s%d%s   active groups: %s%d/%d%s\n" \
    "$BLD" "$RST" "$CYN" "$total_lag" "$RST" "$CYN" "$active_count" "$group_count" "$RST"
  [ "$total_lag" -eq 0 ] && [ "$active_count" -gt 0 ] && \
    printf "%sAll caught up.%s\n" "$GRN" "$RST"
  [ "$ONCE" -eq 0 ] && printf "%s(Ctrl+C to exit — refresh every %ss)%s\n" "$DIM" "$INTERVAL" "$RST"
}

if [ "$ONCE" -eq 1 ]; then
  render_once
  exit 0
fi

trap 'printf "\n%sstopped.%s\n" "$DIM" "$RST"; exit 0' INT
while true; do
  render_once
  sleep "$INTERVAL"
done
