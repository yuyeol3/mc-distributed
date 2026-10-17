# mcd

**상시 서버 없이 친구들과 하나의 마인크래프트 월드를 공유하는 프로그램.**

서버 비용을 내거나 누군가 서버를 계속 켜 둘 필요가 없다. 플레이하려는 사람의 PC가 그때그때 서버가 되고, 월드는 GitHub private repo에 버전으로 보관된다. 오늘은 내가 혼자 건축하고, 내일은 친구가 혼자 들어와도 같은 월드가 이어진다.

- **누가 먼저 켜든 최신 월드**: 접속하는 사람의 PC가 서버가 되고, 마지막으로 저장된 월드에서 시작한다.
- **주소는 항상 `localhost:25565`**: 누가 호스트인지 몰라도 된다. mcd가 알아서 이어준다.
- **호스트가 나가도 계속 플레이**: 다른 사람이 접속해 있으면 서버가 그 사람 PC로 자동으로 옮겨 간다.
- **월드가 갈라지지 않음**: 동시에 두 명이 켜도 서버는 하나만 뜨고, 크래시가 나도 자동으로 복구된다.
- **친구는 설치할 것이 없음**: 인터넷 너머의 친구와도 mcd 하나로 연결된다(VPN이나 포트 포워딩이 필요 없음).

어떻게 동작하는지는 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)(다이어그램 포함), 진행 상황은 [docs/PLAN.md](docs/PLAN.md)에 있다.

## 준비물

| 무엇 | 누가 | 비고 |
|---|---|---|
| Windows 10/11 PC | 모두 | 배포판은 Java 런타임을 포함하므로 Java를 설치하지 않아도 된다 |
| 마인크래프트 Java Edition (월드의 버전) | 모두 | 새 월드는 26.2로 만들어진다. 월드 주인이 버전을 올리면 모두 그 버전으로 맞춘다. mcd 창에 "이 월드는 마인크래프트 OOO용이다"가 뜬다. 정품 계정이 필요하다 |
| GitHub 계정과 토큰 | 모두 | 월드를 보관하는 곳이다. **메인 계정과 따로 만든 계정**을 권한다(아래 참고) |
| Tailscale 계정 | 월드 주인만 | 인터넷 너머의 친구와 할 때만 필요하다. 친구는 필요 없다 |

**GitHub 토큰 만들기**: GitHub → Settings → Developer settings → Personal access tokens → Tokens (classic) → Generate new token. `repo` 권한을 체크한다. 월드 주인은 이 토큰으로 private repo를 만들고, 친구는 초대받은 repo에 접근한다.

**Tailscale 인증 키 만들기**(월드 주인): [Tailscale 관리 화면](https://login.tailscale.com/admin/settings/keys) → Settings → Keys → Generate auth key. **Reusable**을 켜서 만든다. 이 키는 월드 repo에 저장돼 친구들에게 자동으로 전달되므로, 친구들은 Tailscale에 가입하지 않는다.

## 빠른 시작

### 월드 주인

1. `mcd-windows.zip`을 풀고 `mcd/mcd.exe`를 실행한다.
2. 처음 설정 창에서 **내 이름**(다른 사람에게 보일 이름)과 **GitHub 토큰**을 넣고 **Minecraft EULA 동의**에 체크한다.
3. 이어서 나오는 월드 추가 창에서 다음을 넣는다.
   - **새 월드 만들기**를 고르고 repo 이름을 정한다(GitHub에 private repo가 만들어진다).
   - **tailnet 인증 키**: 인터넷 너머의 친구와 할 거라면 Tailscale 인증 키를 넣는다. 같은 집에서만 할 거면 비워 둔다.
4. 월드를 만들면 친구 초대 주소가 나온다. GitHub repo의 Settings → Collaborators에서 친구를 초대한다.
5. 마인크래프트 → 멀티플레이 → 서버 추가 → 주소 **`localhost:25565`** 로 접속한다.

### 친구

1. 월드 주인의 GitHub 초대를 수락한다.
2. `mcd/mcd.exe`를 실행하고 처음 설정(이름, 토큰, EULA)을 마친 뒤, 월드 추가 창에서 **친구의 월드에 참가**를 고르고 월드 주인의 repo 주소(`https://github.com/<계정>/<repo>.git`)를 넣는다. tailnet 인증 키는 비워 둔다(월드에 이미 들어 있다).
3. 마인크래프트에서 **`localhost:25565`** 로 접속한다.

### 플레이할 때 일어나는 일

- **아무도 없을 때 접속하면** 내 PC가 서버가 된다. mcd 창에 "이 PC가 호스트가 됐다"가 뜨고, 서버가 뜰 때까지 접속 중 화면에서 기다렸다가 들어간다(보통 10~20초). 서버 파일(Mojang jar 받기와 패치)은 mcd가 월드를 열 때 미리 준비하므로, 그 PC에서 처음 서버가 될 때도 더 걸리지 않는다. 대신 월드를 처음 열 때 20초 안팎이 더 걸린다.
- **서버 목록을 열기만 해서는** 서버가 뜨지 않는다. 아무도 없으면 목록에 "mcd: 접속하면 서버가 시작됩니다"가 보인다.
- **누가 이미 플레이 중이면** 그 사람의 서버로 이어진다. mcd 창에 "지금 호스트는 OOO이다"가 뜬다.
- **호스트가 mcd를 끄면** 남은 사람들은 잠깐 로딩 화면을 본 뒤 자동으로 다른 사람의 PC로 옮겨 간다.
- **모두 나가면** 5분 뒤 서버가 스스로 월드를 GitHub에 올리고 꺼진다.
- **플레이 중에도** 10분마다 월드가 GitHub에 올라간다.
- **mcd 창을 닫으면** 월드를 저장하고 올린 뒤 끝난다. 그냥 닫으면 된다.
- **새 버전이 나오면** mcd를 켤 때 업데이트할지 물어본다. 동의하면 받아서 검증한 뒤 mcd를 껐다가(호스트였다면 평소처럼 저장하고 넘김) 바꾼 다음 다시 켠다. 같은 월드를 쓰는 친구들끼리는 버전을 맞추는 게 좋다.

### mcd 창에서 할 수 있는 것

| 버튼 | 하는 일 |
|---|---|
| **월드** 목록 | 여러 월드를 오갈 수 있다. 다른 월드를 고르면 지금 월드를 저장하고(호스트였다면 접속 중인 사람에게 넘기고) 고른 월드로 바꾼다 |
| **월드 추가** | 새 월드를 만들거나 친구의 월드에 참가한다 |
| **목록에서 빼기** | 지금 월드를 목록에서 뺀다. GitHub의 월드와 이 PC의 사본은 지우지 않으므로 다시 추가할 수 있다 |
| **버전 올리기** | 지금 월드의 마인크래프트(Paper) 버전을 올린다. 누가 플레이 중이면 안 되고, 되돌릴 수 없다. 모두 그 버전의 마인크래프트로 접속해야 한다 |
| **설정** | 내 이름, GitHub 토큰, 내 서버 주소를 바꾼다. 토큰은 GitHub에 확인한 뒤에 저장한다 |

## 문제 해결

| 증상 | 원인과 해결 |
|---|---|
| 처음 접속할 때 "시간 초과"로 끊김 | mcd는 서버가 뜰 때까지(최대 3분) 접속을 붙잡아 둔다. 그래도 끊기면 다시 접속하면 된다(서버는 그사이에도 계속 뜬다) |
| "지금 호스트는 OOO"인데 접속이 안 됨 | 그 사람의 PC가 갑자기 꺼졌을 수 있다. 최대 10분 뒤 자동으로 다른 PC가 이어받으니, 그 뒤에 다시 접속한다 |
| 접속 화면에서 버전이 맞지 않다고 나옴 | 마인크래프트 버전을 mcd 창에 뜨는 월드 버전으로 바꾼다 |
| "Java N 이상이 필요한데" | 월드가 이 mcd보다 새 Paper를 쓴다. mcd를 새 버전으로 바꾼다 |
| "토큰을 확인하지 못했다" | 토큰이 틀렸거나 `repo` 권한이 없다. 토큰을 다시 만든다 |
| "원격에 올리지 못했다" (새 월드) | 같은 이름의 repo가 이미 있거나 비어 있지 않다. 다른 이름을 쓴다 |
| 토큰이나 이름을 바꾸고 싶음 | mcd 창의 **설정** |
| 설정을 처음부터 다시 하고 싶음 | `%USERPROFILE%\.mcd\settings.properties`를 지우고 다시 실행한다 |
| GitHub repo에 `recovery/...` 브랜치가 생김 | PC가 갑자기 꺼진 사이 다른 사람이 이어서 플레이해서, 꺼졌던 PC의 마지막 몇 분이 따로 보관된 것이다. 메인 월드는 갈라지지 않았다. 필요 없으면 지워도 된다 |
| Windows 방화벽 허용 창이 뜸 | mcd와 함께 도는 Java(Paper)나 `mcd-net`이 네트워크를 쓰려는 것이다. 허용한다 |

## CLI 사용법

배포판의 `mcd/mcd-cli.exe`(또는 직접 빌드한 `build/install/mcd/bin/mcd`)로 GUI 없이 쓸 수 있다. GitHub 토큰은 환경 변수 `MCD_GITHUB_TOKEN`으로 넘긴다.

```sh
# 새 월드 만들기 (원격 repo는 비어 있어야 한다)
mcd-cli init --remote https://github.com/<계정>/<repo>.git --dir worlds/survival --tailnet-auth-key <키>

# 노드 띄우기: 이후 마인크래프트에서 localhost:25565로 접속한다
mcd-cli play --remote https://github.com/<계정>/<repo>.git --dir worlds/survival --node <내 이름> --accept-eula
```

```sh
# 월드의 마인크래프트(Paper) 버전 올리기: 모두 나간 상태에서
mcd-cli upgrade-paper --remote https://github.com/<계정>/<repo>.git --dir worlds/survival --node <내 이름> --paper 26.2
```

`play` 중 콘솔 입력은 내 PC가 호스트일 때 Paper 콘솔로 넘어간다(예: `op <이름>`, `difficulty hard`). `stop`이나 Ctrl+C를 누르면 저장하고 끝난다.

| 명령 | 옵션 | 설명 |
|---|---|---|
| `init` | `--remote <git URL>` | 비어 있는 원격 repo |
| | `--dir <폴더>` | 월드를 둘 새 폴더 |
| | `--paper <버전 또는 버전-빌드>` | 월드의 마인크래프트(Paper) 버전. 없으면 기본값(26.2-133), 버전만 주면 그 버전의 최신 안정 빌드 |
| | `--tailnet-auth-key <키>` | 인터넷 너머 친구와 하려면. 월드에 기록돼 모두에게 전달된다 |
| | `--tailnet-control-url <주소>` | 조정 서버. 비우면 Tailscale. Headscale을 직접 운영하면 그 주소 |
| | `--offline` | online mode를 끈다(테스트용, 정품 인증 없음) |
| `play` | `--remote`, `--dir`, `--node <이름>` | 필수 |
| | `--accept-eula` | [Minecraft EULA](https://aka.ms/MinecraftEULA)에 동의 |
| | `--listen <포트>` | 마인크래프트가 접속할 이 PC의 포트(기본 25565) |
| | `--port <포트>` | 이 PC가 호스트일 때 Paper 포트(기본 25566) |
| | `--address <호스트:포트>` | tailnet 없이 같은 네트워크에서 쓸 때 다른 PC가 내 Paper에 접속할 주소 |
| | `--cache <폴더>` | Paper jar를 받아 둘 곳(기본 `~/.mcd/cache`) |
| | `--ttl-seconds`, `--handoff-port` | 테스트용 |
| `update` | | 새 버전이 있으면 받아서 검증해 두고, 이 설치 폴더의 mcd가 모두 끝나면 바꾼다 |
| `upgrade-paper` | `--remote`, `--dir`, `--node` | 필수 |
| | `--paper <버전 또는 버전-빌드>` | 올릴 버전. 버전만 주면(예: `26.2`) 그 버전의 최신 안정 빌드. 호스트가 있는 동안은 안 되고, 옛 버전으로는 되돌릴 수 없다 |

## 파일 위치

| 위치 | 내용 |
|---|---|
| `~/.mcd/settings.properties` | GUI 설정(이름, 토큰, 서버 주소, 월드 목록). 토큰이 평문으로 저장된다 |
| `~/.mcd/worlds/<repo 이름>/` | 월드의 로컬 사본이자 Paper 서버 폴더(git 작업 트리) |
| `~/.mcd/cache/` | Paper jar(월드가 쓰는 빌드를 처음 쓸 때 받는다)와 검증한 해시(`.sha256`) |
| `~/.mcd/tailnet/` | tailnet 노드 키. 월드 폴더 밖에 둬서 GitHub에 올라가지 않는다 |

## 직접 빌드하기

필요한 것: JDK 25, Go(`mcd-net` 빌드), Node.js(E2E 테스트 봇).

```sh
./gradlew installDist      # build/install/mcd (JAVA_HOME이 Java 25여야 실행된다)
./gradlew packageZip       # build/distributions/mcd-windows.zip(Java 런타임 포함)과 .sha256
./gradlew test             # 단위 테스트: 소유권 프로토콜, 중계, 설정, GitHub 클라이언트
./gradlew paperTest        # 실제 Paper + Mineflayer 봇 E2E (느림)
```

`paperTest`를 돌리려면 `nodes/_cache/paper-26.1.2-74.jar`가 있어야 하고 `e2e/`에서 `npm install`을 해 둬야 한다. 테스트 봇(Mineflayer)이 26.1까지만 접속할 수 있어서 봇 E2E는 26.1.2 월드로 돈다. 다른 Paper 빌드는 봇 없이 `./gradlew paperTest --tests '*PaperCompatibilityTest*' -Dmcd.paperBuild=<빌드>`로 확인한다(mcd가 기대는 콘솔 동작, 26.1.2 월드의 업그레이드). tailnet 시나리오는 조정 서버가 있을 때만 돈다(`MCD_TEST_CONTROL_URL`, `MCD_TEST_AUTH_KEY`, 개발 중에는 Headscale을 썼다). 테스트 구성은 [docs/ARCHITECTURE.md의 테스트 전략](docs/ARCHITECTURE.md#11-테스트-전략)을 참고한다.

```text
src/main/java/mcd/
  cli/      CLI (init, play)
  gui/      GUI(메인 창, 설정, 월드 추가)
  node/     한 PC의 노드: 중계, 라우팅, 호스트 기간 관리, 이관
  lock/     git push를 락으로 쓰는 소유권 프로토콜
  net/      로컬 중계, tailnet 보조 프로그램 연동
  server/   Paper 프로세스 제어, 고정 빌드 다운로드
  world/    월드 repo 생성과 초기 파일
net-helper/ tailnet 보조 프로그램 mcd-net (Go, tsnet)
e2e/        테스트용 헤드리스 플레이어 (Mineflayer)
experiments/ 실험 기록 (E1′ ~ E6)
```

## 알려진 한계

- 호스트 PC가 갑자기 꺼지면, 다른 PC가 이어받기까지 최대 10분 걸린다. 그동안의 플레이(마지막 저장 이후 최대 10분)는 recovery 브랜치로 따로 보관된다.
- 호스트를 넘길 때 나가는 PC의 플레이어도 같이 끊긴다.
- tailnet 인증 키는 월드 repo에 들어가므로 collaborator에게 보인다. GitHub 토큰은 설정 파일에 평문으로 저장된다.
- tailnet으로 들어온 플레이어는 서버에서 127.0.0.1로 보인다(IP 차단 기능은 쓸 수 없다).
- 설치 파일(.msi)은 아직 없다(zip을 풀어서 쓴다). 자동 업데이트는 v0.2.0부터 들어 있어서, v0.1.0은 한 번 직접 바꿔야 한다.
- 자동 업데이트는 받은 파일을 GitHub 릴리스의 SHA-256으로 검증한다. 다운로드가 깨지지 않았는지만 보장하고, 코드 서명은 하지 않는다.
- 아직 실제 원격 친구 환경에서는 확인하지 않았다(같은 PC의 VM과 테스트 봇으로 확인). 정품 클라이언트로는 한 PC에서 접속, 정품 인증, 첫 기동 대기를 확인했다.
- GitHub 이용 정책에는 과도한 자동 활동 금지 조항이 있다. mcd는 push 빈도를 낮게 유지하지만, 혹시 모를 계정 제재가 메인 계정에 미치지 않도록 월드 전용 계정을 권한다.

## 라이선스

[MIT](LICENSE). 배포판에 함께 들어가는 Java 런타임, JGit, tsnet은 각자의 라이선스를 따르고, Paper는 배포판에 넣지 않고 월드가 정한 빌드를 처음 쓸 때 받는다.
