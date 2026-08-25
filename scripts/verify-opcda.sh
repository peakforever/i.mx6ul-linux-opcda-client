#!/bin/sh
# OPC DA client on-device acceptance (iteration 1 + 2)
# Usage: export OPC_PASSWORD='<opcuser password>'; sh verify-opcda.sh [--reconnect] [--collect]
# APP=/home/ecu/opc2ecu by default; override with APP=/path env
# BusyBox-compatible: no `timeout`, no GNU ps/head flags.
set -u

APP=${APP:-/home/ecu/opc2ecu}
JAVA=${JAVA:-$APP/runtime/bin/java}
JAR=${JAR:-$APP/lib/opcda-probe.jar}
CONF=${CONF:-$APP/config/opc.properties}
POINTS=${POINTS:-$APP/config/points.json}
WIN_HOST=192.168.1.2

# BusyBox has no `timeout`: run a command in the background and kill it
# after N seconds. Returns the command's exit code (143 if it was killed).
run_with_timeout() {
    _secs=$1; shift
    "$@" &
    _pid=$!
    ( sleep "$_secs" && kill "$_pid" 2>/dev/null ) &
    _killer=$!
    wait "$_pid"
    _rc=$?
    kill "$_killer" 2>/dev/null
    return $_rc
}

pass=0; fail=0
ok()  { pass=$((pass+1)); echo "PASS: $1"; }
bad() { fail=$((fail+1)); echo "FAIL: $1"; }

[ -n "${OPC_PASSWORD:-}" ] || { echo "ERROR: set OPC_PASSWORD first"; exit 2; }
[ -x "$JAVA" ] && ok "JRE executable: $JAVA" || { bad "JRE not found: $JAVA"; exit 2; }
[ -f "$JAR" ] && ok "jar present: $JAR" || { bad "jar missing: $JAR"; exit 2; }

echo "=== Phase 1: offline self-tests ==="
if run_with_timeout 30 "$JAVA" -jar "$JAR" --self-test-protocol >/tmp/opcda-p1.log 2>&1; then
  ok "self-test-protocol"; grep RESULT /tmp/opcda-p1.log
else bad "self-test-protocol"; tail -5 /tmp/opcda-p1.log; fi
if ( OPC_PASSWORD=x; run_with_timeout 30 "$JAVA" -jar "$JAR" --check-config "$CONF" >/tmp/opcda-p1b.log 2>&1 ); then
  ok "check-config"; grep RESULT /tmp/opcda-p1b.log
else bad "check-config"; tail -5 /tmp/opcda-p1b.log; fi

echo "=== Phase 2: connection & read (needs Windows+Matrikon online) ==="
if run_with_timeout 90 "$JAVA" -jar "$JAR" --list-server "$CONF" >/tmp/opcda-p2.log 2>&1; then
  ok "list-server ($(grep -c '\[SERVER' /tmp/opcda-p2.log) servers)"
else bad "list-server"; tail -5 /tmp/opcda-p2.log; fi
if run_with_timeout 90 "$JAVA" -jar "$JAR" --list-items "$CONF" >/tmp/opcda-p2b.log 2>&1; then
  ok "list-items ($(grep -c '\[ITEM' /tmp/opcda-p2b.log) items)"
else bad "list-items"; tail -5 /tmp/opcda-p2b.log; fi
if run_with_timeout 120 "$JAVA" -jar "$JAR" "$CONF" >/tmp/opcda-p2c.log 2>&1; then
  ok "read 10 samples"; grep RESULT /tmp/opcda-p2c.log
else bad "read 10 samples"; tail -5 /tmp/opcda-p2c.log; fi

echo "=== Phase 4: wrong password -> exit code 3 ==="
( OPC_PASSWORD=definitely-wrong; run_with_timeout 30 "$JAVA" -jar "$JAR" "$CONF" >/tmp/opcda-p4.log 2>&1 )
rc=$?
if [ "$rc" -eq 3 ]; then ok "wrong-password exit code 3"; else bad "wrong-password exit code=$rc (want 3)"; tail -3 /tmp/opcda-p4.log; fi

echo "=== Phase 3: reconnect (requires root; 30s network drop) ==="
if [ "${1:-}" = "--reconnect" ]; then
  if command -v iptables >/dev/null 2>&1; then
    ( run_with_timeout 90 "$JAVA" -jar "$JAR" "$CONF" >/tmp/opcda-p3.log 2>&1 ) &
    probe_pid=$!
    sleep 3
    iptables -I OUTPUT 1 -d "$WIN_HOST" -j DROP && echo "  network to $WIN_HOST dropped"
    sleep 30
    iptables -D OUTPUT -d "$WIN_HOST" -j DROP && echo "  network restored"
    wait "$probe_pid"
    if grep -q '\[GAP\]' /tmp/opcda-p3.log; then ok "reconnect after 30s drop ([GAP] present)"; grep GAP /tmp/opcda-p3.log
    else bad "no [GAP] in log"; tail -5 /tmp/opcda-p3.log; fi
  else
    echo "  SKIP: iptables not available (need root)"
  fi
else
  echo "  SKIP: pass --reconnect to run the 30s network-drop test"
fi

echo "=== Phase 5: collect + UDP datagrams (iteration 2) ==="
if [ "${1:-}" = "--collect" ] || [ "${2:-}" = "--collect" ]; then
  if command -v nc >/dev/null 2>&1; then
    ( nc -u -l -p 5353 > /tmp/opcda-udp.bin 2>/dev/null ) & ncpid=$!
    sleep 1
    ( OPC_PASSWORD="$OPC_PASSWORD"; run_with_timeout 12 "$JAVA" -jar "$JAR" --collect "$POINTS" >/tmp/opcda-p5.log 2>&1 )
    sleep 1
    kill "$ncpid" 2>/dev/null
    sz=$(wc -c < /tmp/opcda-udp.bin 2>/dev/null || echo 0)
    if [ "$sz" -gt 0 ]; then
      ok "UDP capture non-empty: $sz bytes"
      echo "  first 64 bytes:"; od -A x -t x1 /tmp/opcda-udp.bin | head -n 4
    else
      bad "UDP capture empty (no datagrams received on 5353)"
    fi
    if grep -q '\[START\]' /tmp/opcda-p5.log; then ok "--collect started ($(grep START /tmp/opcda-p5.log | sed 's/.*\[START\] //'))"
    else bad "--collect did not start"; tail -5 /tmp/opcda-p5.log; fi
  else
    echo "  SKIP: nc not found; cannot capture UDP"
  fi
else
  echo "  SKIP: pass --collect to run the UDP send test"
fi

echo "=== Phase 6: resource snapshot ==="
free -m 2>/dev/null | head -n 2 || true
ps w 2>/dev/null | grep '[j]ava' | head -n 3 || true

echo "=============================================="
echo "RESULT: PASS=$pass FAIL=$fail"
[ "$fail" -eq 0 ]
