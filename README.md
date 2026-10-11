# mcd

상시 서버 없이 친구들이 하나의 마인크래프트 월드를 공유한다. 플레이하려는 사람의 PC가 그때그때 Paper 서버가 되고, 월드는 git 원격 저장소(GitHub private repo 등)에 버전으로 보관된다.

## 배포판

`./gradlew packageZip`은 Java 25 런타임을 포함한 실행 폴더를 `build/distributions/mcd-windows.zip`으로 묶는다. 사용자는 Java를 설치할 필요가 없다. 설치 파일(.exe/.msi)은 WiX Toolset이 있어야 만들 수 있어서 아직 없다.

- `mcd/mcd.exe`: GUI. 처음 실행하면 설정 마법사(이름, GitHub 토큰, 새 월드 만들기 또는 친구의 월드 참가, EULA 동의)가 뜨고, 그다음 창에서 노드가 돈다. 창을 닫으면 저장하고 락을 반납한 뒤 끝난다. 설정은 `~/.mcd/settings.properties`, 월드는 `~/.mcd/worlds/<repo 이름>`에 둔다.
- `mcd/mcd-cli.exe`: 아래 CLI와 같다.

## 사용법 (MVP, CLI)

개발 중에는 `./gradlew installDist`로 만든 스크립트를 쓴다. 이 스크립트는 `JAVA_HOME`의 Java를 쓰므로 `JAVA_HOME`이 Java 25를 가리켜야 한다. 배포판의 `mcd.exe`는 같은 인자를 받는다.

```sh
./gradlew installDist
# 새 월드 만들기: 빈 원격 repo가 필요하다
build/install/mcd/bin/mcd init --remote https://github.com/<계정>/<repo>.git --dir worlds/survival
# 노드 띄우기: 이후 마인크래프트에서 localhost:25565로 접속한다
build/install/mcd/bin/mcd play --remote https://github.com/<계정>/<repo>.git --dir worlds/survival --node <내 이름> --accept-eula
```

- GitHub 토큰은 환경 변수 `MCD_GITHUB_TOKEN`으로 넘긴다.
- **마인크래프트의 서버 주소는 항상 `localhost:25565`다.** 접속하면 mcd가 현재 호스트로 이어주고, 호스트가 없으면 이 PC가 서버가 된다. 아무도 접속하지 않은 채 5분이 지나면 서버는 스스로 월드를 올리고 내려간다.
- 호스트일 때 콘솔 입력은 Paper 콘솔로 넘어간다. `stop`을 입력하거나 Ctrl+C를 누르면 저장하고 락을 반납한 뒤 끝난다.
- **인터넷 너머의 친구와 하려면** 월드를 만들 때 tailnet 인증 키를 넣는다(`init --tailnet-auth-key`, GUI 마법사의 "tailnet 인증 키"). 그러면 모든 노드가 mcd에 내장된 tailnet 클라이언트(tsnet)로 서로 접속한다. 친구는 Tailscale을 설치하거나 로그인하지 않는다. 월드 주인만 Tailscale 관리 화면에서 재사용 가능한 키를 한 번 발급한다(Headscale 같은 다른 조정 서버도 `--tailnet-control-url`로 쓸 수 있다).
- tailnet 없이 같은 네트워크에서만 쓸 때는 `--address <내 주소>:25566`으로 다른 PC가 접속할 주소를 넘긴다.
- `--accept-eula`는 [Minecraft EULA](https://aka.ms/MinecraftEULA)에 동의한다는 뜻이다.

## 동작 방식

- **tailnet**: 월드 repo에 `mcd-network.properties`가 있으면 mcd가 `mcd-net`(Go, tsnet)을 띄워 tailnet에 붙는다. 다른 PC로 나가는 중계 연결은 `mcd-net`의 SOCKS5로, 들어오는 연결은 tailnet의 Paper 포트에서 로컬 Paper로 넘어온다. 락에는 tailnet 주소가 적힌다(실험 E5). 노드 키는 월드 폴더 밖에 둔다.
- **중계**: mcd는 `localhost:25565`에서 마인크래프트 연결을 받아 현재 호스트의 Paper로 바이트 그대로 넘긴다(`Relay`). 연결마다 락을 확인하고, 호스트가 없으면 락을 잡고 Paper를 띄운 뒤 이어준다. 클라이언트의 읽기 제한 때문에 Paper 기동을 25초까지만 기다리고, 넘으면 연결을 닫는다(다시 접속하면 된다).
- **락**: 월드 repo의 `main` 안에 `mcd-lock.properties`가 있다. 모든 상태 변경은 원격 `main` 위에 커밋 하나를 쌓고 force 없이 push하는 것이고, 동시에 push하면 하나만 성공한다(실험 E2). 락을 잃은 호스트의 push는 git이 거절한다.
- **호스트 이관**: 다른 사람이 접속해 있는데 호스트가 나가면, 마지막 스냅샷과 함께 락을 반납하고 곧바로 모두를 각자 PC의 `localhost:25565`로 transfer한다. 각자의 mcd 중계가 빈 락을 겨루고 한 노드만 이겨서 Paper를 띄우며, 나머지 중계는 그 Paper가 열릴 때까지 다시 시도한다. 플레이어는 잠깐 접속 중 화면을 본 뒤 같은 월드로 들어간다.
- **lease**: 호스트는 2분마다 heartbeat를, 10분마다 월드 스냅샷을 push한다. 다른 노드는 같은 `main`을 자기 시계로 10분 동안 관찰하면 호스트가 죽었다고 보고 이어받는다. 호스트는 8분 동안 갱신하지 못하면 스스로 서버를 내린다.
- **스냅샷**: `save-off` → `save-all flush` → 로컬 커밋 → `save-on` → push. Paper 자동 저장은 `save-off` 중에도 레벨 데이터를 써서 끄고, 저장은 이 스냅샷이 맡는다(실험 E3).
- **크래시 복구**: 다시 실행했을 때 push하지 못한 변경이 남아 있으면, 그동안 아무도 락을 가져가지 않았을 때만 `main`에 이어서 올린다. 그 밖의 경우는 `recovery/<노드>-<epoch>-<커밋>` 브랜치에만 올려서 사람이 나중에 살펴볼 수 있게 한다.
- Paper 빌드는 `26.1.2-74`로 고정하고 월드 repo에 기록한다(`mcd-paper-build.txt`). 26.1+ 월드는 다운그레이드할 수 없어서 모든 호스트가 같은 빌드를 써야 한다.

## 테스트

- `./gradlew test`: 소유권 프로토콜(로컬 bare repo + 가짜 시계), push CAS 동작
- `./gradlew paperTest`: 실제 Paper와 Mineflayer 봇으로 E3 실험과 P0 시나리오 5개. `nodes/_cache/paper-26.1.2-74.jar`와 `e2e/`의 `npm install`이 필요하다.
- 실험 결과는 `experiments/`에 있다.

## 알려진 한계

- tailnet 인증 키는 월드 repo에 들어가므로 collaborator에게 보인다. 권한을 좁힌 키를 쓴다.
- 새 폴더의 첫 Paper 기동은 25초를 넘을 수 있어서, 그때는 첫 접속이 끊기고 다시 접속해야 한다.
- GitHub 토큰은 `~/.mcd/settings.properties`에 평문으로 저장된다. OS 자격 증명 저장소 연동은 나중에 한다.
- Paper는 stdin이 닫혀도 계속 돈다. 그래서 Paper JVM에 작은 에이전트(`ParentWatchdog`)를 붙여서, mcd가 죽으면 Paper도 저장하고 종료하게 했다. 다시 실행하면 그동안 아무도 락을 가져가지 않았을 경우 그 플레이 기록이 `main`에 이어진다(`McdCrashTest`). PC 전체가 꺼지면 TTL이 지난 뒤 다른 노드가 이어받는다.
- 호스트가 크래시로 사라지면(정상 종료가 아니면) 플레이어는 TTL이 지난 뒤 직접 다시 접속해야 한다. 정상 종료 때는 자동으로 옮겨 간다.
- GitHub 이용 정책에는 과도한 자동 활동 금지 조항이 있다. 월드 저장용 계정이나 organization을 메인 계정과 분리하는 것을 권한다.
