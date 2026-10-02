#!/usr/bin/env bash
# Run the unchanged release tasks with bounded, non-secret performance evidence.
set -euo pipefail
phase="$1"
shift
diagnostics="artifact/build-performance/$phase"
mkdir -p "$diagnostics"
{
  date -u
  nproc
  free -m
} > "$diagnostics/host.txt"
vmstat -w 5 > "$diagnostics/vmstat.txt" &
vmstat_pid=$!
(
  while true; do
    date -u
    ps -C java -o pid,pcpu,pmem,rss,etime,comm || true
    sleep 30
  done
) > "$diagnostics/java-processes.txt" &
process_pid=$!
cleanup() {
  kill "$vmstat_pid" "$process_pid" 2>/dev/null || true
  wait "$vmstat_pid" "$process_pid" 2>/dev/null || true
}
trap cleanup EXIT
/usr/bin/time -v -o "$diagnostics/gradle-time.txt" \
  gradle --no-daemon --console=plain --profile "$@"
