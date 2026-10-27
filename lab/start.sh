#!/bin/sh
# 실험실 시나리오가 정한 네트워크 조건(iptables, tc)을 mcd보다 먼저 건다. tailnet이 붙기 전부터 걸려 있어야 한다.
set -e
if [ -n "$LAB_SETUP" ]; then sh -c "$LAB_SETUP"; fi
exec java -Dmcd.netHelper=/opt/mcd/mcd-net -cp "/opt/mcd/classes:/opt/mcd/lib/*" mcd.cli.Main "$@"
