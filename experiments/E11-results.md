# E11: 도커 실험실 (컨테이너마다 PC 하나)

날짜: 2026-10-27. Windows 11 + Docker Desktop(Linux VM, 메모리 8GB, CPU 22개), 노드 이미지 eclipse-temurin:25-jre,
Headscale v0.29.4, derper(tailscale.com v1.102.5), git daemon. 노드 TTL 60초(관찰 3초, heartbeat 12초).

## 왜

지금까지의 E2E는 한 PC에서 노드를 포트만 다르게 띄웠다. 그래서 모두 같은 포트(25565)를 쓰는 실제 배포 구성, 각자 자기
PC의 중계로 옮겨 가는 handoff, tailnet을 거친 연결, 네트워크 장애를 만들 수 없었다. 실제 사용 로그에서 호스트가 자기
health에 답하지 않는 문제가 나온 것을 계기로 만들었다.

## 구성

- 컨테이너 하나가 PC 하나다. 노드는 중계 25565, Paper 25566, health 25567을 똑같이 쓴다.
- 봇(Mineflayer)은 자기 노드 컨테이너의 네트워크를 같이 써서 `localhost:25565`로 접속한다. transfer도 각자 자기 PC로 간다.
- 도커 네트워크 하나에 git daemon(월드 원격), Headscale, derper를 고정 주소로 둔다. DERP 맵은 실험실 derper 하나뿐이라
  tailnet은 공개 DERP를 쓰지 않는다.
- 노드 이미지에는 mcd.jar 대신 클래스 폴더를 넣는다. 버전이 없어서 업데이트 확인(GitHub API)을 하지 않는다.
- 네트워크 장애는 노드 컨테이너(NET_ADMIN)에서 iptables와 tc로 건다. tailnet이 붙기 전부터 걸어야 하는 조건은 시작
  스크립트(`lab/start.sh`)가 mcd보다 먼저 건다.
- 버전 혼합용 옛 노드는 `-Plab.oldRef`의 소스를 꺼내 그 커밋의 빌드로 만든다. 기본은 744d508이다(아래 v0.6.0 참고).
- 실행: `./gradlew labTest`. 시나리오마다 새 실험실을 띄우고 끝나면 지운다.

## 시나리오 (`LabScenarioTest`)

| 시나리오 | 확인한 불변식 |
|---|---|
| handoff (A, B, C) | 남은 플레이어가 각자 자기 PC로 옮겨 가고 B나 C 한 곳만 epoch 2 호스트가 된다. 인벤토리가 이어진다. health 오판 없음. main의 epoch가 줄지 않고 recovery 없음 |
| 느린 네트워크 | 위와 같다. 실험실 서브넷으로 가는 트래픽에 60ms±10ms, 손실 0.5% |
| DERP만 가능 | 위와 같다. 노드 사이 UDP를 막아 tailnet이 derper를 거친다 |
| 버전 혼합 | 옛 A → 지금 B → 옛 C로 월드가 넘어가고 인벤토리가 이어진다. recovery 없음 |
| 멈춘 호스트 | `docker pause` 뒤 B가 health 실패를 보고 lease 전에 인수한다. 깨어난 A의 Paper는 15초 안에 사라지고 A는 B를 따라간다. A의 마지막 스냅샷 이후 진행분은 `recovery/A-1-*`에만 있다 |
| 호스트가 git과 끊김 | A가 실행 허가 만료로 스스로 Paper를 내린다. git이 끊긴 채로도 A의 mcd가 2분 안에 호스트 상태에서 빠져나온다. B가 Paper를 시작하는 순간 A의 Paper는 없다 |
| 호스트가 tailnet과 끊김 | A는 멀쩡하지만 B에서는 죽은 writer로 보여 인수가 일어난다. A는 git에서 소유권 변경을 보고 내려가고, B가 Paper를 시작하는 순간 A의 Paper는 없다 |

## 찾아서 고친 것

| 문제 | 어디서 드러났나 | 수정 |
|---|---|---|
| 호스트가 되는 동안 돈 관찰 루프가 자기 락 상태를 지워, 호스트가 health에 답하지 않음. 다른 PC가 멀쩡한 호스트를 인수하고, 인수한 쪽이 다시 같은 상태가 되어 번갈아 인수. 같은 경합에서 관찰 루프가 `peek()`로 돌고 있는 월드를 커밋해 main에 올리고, 그 바람에 호스트가 스스로 내려감 | 실제 사용 로그, `FastFailoverTest`에 probe 추가, 버전 혼합(v0.6.0 노드) | 관찰 루프가 session을 모니터 안에서 확인(744d508) |
| tailnet으로 들어온 연결이 끝나도 mcd-net이 Paper에 알리지 않아 나간 플레이어가 30초쯤 남음 | `TailnetScenarioTest`(Headscale) | mcd-net이 반대쪽을 CloseWrite |
| 경쟁에서 진 중계가 기동 중인 새 호스트에 붙었다가 끊김(tailnet은 포트를 늘 열어 둠). 그 PC의 플레이어는 직접 다시 접속해야 함 | handoff | 호스트가 첫 응답을 보낼 때까지를 연결로 보고 재시도 |
| git fetch/push에 timeout이 없어 원격이 응답 없이 끊기면 호스트 루프가 수 분간 멈춤(안전은 Paper watchdog이 지킴) | 호스트가 git과 끊김 | fetch/push에 30초 timeout |
| 정상 종료 중인 호스트가 Paper를 내린 뒤 마지막 push 동안 health에 답하지 않음. 느린 회선에서는 15초를 넘겨 다른 PC가 인수할 수 있고, 그러면 마지막 저장이 recovery로 밀림 | 느린 네트워크 | health는 소유권 확인(guard)만 본다 |
| handoff에서 transfer 뒤 고정 2초 만에 Paper를 닫아, 느린 회선에서 막 접속한 플레이어가 transfer를 받지 못하고 끊김 | 느린 네트워크 | 모두 떠날 때까지 최대 30초 기다림 |
| 서버 목록 확인을 기동 중인 호스트로 넘기면 tailnet에서는 곧바로 끊겨 "연결할 수 없음" | 느린 네트워크(봇은 접속 전에 ping) | 호스트가 첫 응답을 보낼 때만 넘기고, 아니면 중계가 직접 답함 |

## 판단

- recovery 브랜치는 오류가 아니다. 소유권을 잃은 PC는 공유하지 못한 진행분을 main에 섞지 않고 따로 보관한다. 시나리오마다
  있어야 하는지(멈춘 호스트, 분할) 없어야 하는지(handoff, 버전 혼합)를 따로 확인한다.
- 느린 네트워크 조건: 처음 건 100ms±30ms, 손실 1%(구간마다)에서는 접속 직후의 청크 데이터에 keepalive가 밀려 Paper가
  봇을 내보냈다. mcd가 아니라 마인크래프트 자체가 버티지 못하는 조건이라 60ms±10ms, 손실 0.5%로 낮췄다. 지연은 실험실
  서브넷으로 가는 트래픽에만 건다. 처음 기동할 때 인터넷에서 받는 Mojang jar까지 느려져 노드가 준비되지 않았기 때문이다.
- 호스트가 tailnet과 끊기면 health는 보는 쪽 기준으로 판정하므로 멀쩡한 호스트가 인수당하고 그 PC의 플레이어는 튕긴다.
  writer가 둘이 되거나 월드가 갈라지지 않으므로 감수한다. 같은 이유로 분할이 이어지는 동안 양쪽에서 접속할 때마다 번갈아
  인수할 수 있다(시험하지 않음).

## v0.6.0 노드의 동작

버전 혼합 시나리오를 v0.6.0(76d231d) 노드로 돌리면 흔들렸다. 실패한 실행의 원격 이력은 이랬다.

```
acquire C epoch 3
unsaved changes found at startup by C
```

옛 C가 호스트가 된 직후 관찰 루프 경합이 나서 `peek()`의 `reconcileLocalChanges()`가 Paper가 돌고 있는 월드 폴더를
커밋해 main에 올렸다. 저장을 끄고 flush하는 절차를 거치지 않은 스냅샷이다. 원격 main이 호스트 세션이 모르는 커밋으로
움직이자 세션은 "다른 노드가 락을 가져갔습니다"로 보고 스스로 서버를 내렸고, 막 들어온 플레이어는 튕겼다. 인수로 호스트가
되면(30초 대기가 관찰 주기 30초보다 길다) 이 경합은 거의 항상 난다. v0.6.0으로 플레이한 월드는 원격 이력에 호스팅 중에
생긴 `unsaved changes found at startup` 커밋이 있는지 확인할 만하다. 수정이 들어간 744d508 노드로 바꾼 뒤 세 번 연속 통과했다.

## 한계

- 컨테이너는 Linux다. Windows 고유 동작(Ctrl+C 전달, 파일 잠금)은 Windows 테스트로 따로 본다.
- 원격은 git daemon이다. GitHub의 지연, rate limit, SSH 연결 비용은 흉내 내지 않는다.
- 노드마다 처음 기동할 때 인터넷에서 Mojang jar를 받는다. 이미지에 미리 넣는 것은 나중으로 미뤘다.
- `docker pause`는 프로세스를 얼릴 뿐 시계는 계속 간다. 실제 절전 복귀와 시계 동작이 다를 수 있다.
- 시나리오는 수정 전후로 한두 번씩 돌렸다. 여러 번 반복했을 때의 안정성은 아직 모른다.
