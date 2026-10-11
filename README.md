# mcd

상시 서버 없이 친구들이 하나의 마인크래프트 월드를 공유한다. 플레이하려는 사람의 PC가 그때그때 Paper 서버가 되고, 월드는 git 원격 저장소(GitHub private repo 등)에 버전으로 보관된다.

## 사용법 (MVP, CLI)

Java 25가 필요하다. 실행 스크립트는 `JAVA_HOME`의 Java를 쓰므로 `JAVA_HOME`이 Java 25를 가리켜야 한다(배포판에서는 jpackage로 런타임을 함께 넣을 예정).

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
- 다른 PC가 내 Paper에 접속하려면 `--address <내 주소>:25566`을 넘긴다(네트워크 방식은 아직 정하지 않음).
- `--accept-eula`는 [Minecraft EULA](https://aka.ms/MinecraftEULA)에 동의한다는 뜻이다.

## 동작 방식

- **중계**: mcd는 `localhost:25565`에서 마인크래프트 연결을 받아 현재 호스트의 Paper로 바이트 그대로 넘긴다(`Relay`). 연결마다 락을 확인하고, 호스트가 없으면 락을 잡고 Paper를 띄운 뒤 이어준다. 클라이언트의 읽기 제한 때문에 Paper 기동을 25초까지만 기다리고, 넘으면 연결을 닫는다(다시 접속하면 된다).
- **락**: 월드 repo의 `main` 안에 `mcd-lock.properties`가 있다. 모든 상태 변경은 원격 `main` 위에 커밋 하나를 쌓고 force 없이 push하는 것이고, 동시에 push하면 하나만 성공한다(실험 E2). 락을 잃은 호스트의 push는 git이 거절한다.
- **lease**: 호스트는 2분마다 heartbeat를, 10분마다 월드 스냅샷을 push한다. 다른 노드는 같은 `main`을 자기 시계로 10분 동안 관찰하면 호스트가 죽었다고 보고 이어받는다. 호스트는 8분 동안 갱신하지 못하면 스스로 서버를 내린다.
- **스냅샷**: `save-off` → `save-all flush` → 로컬 커밋 → `save-on` → push. Paper 자동 저장은 `save-off` 중에도 레벨 데이터를 써서 끄고, 저장은 이 스냅샷이 맡는다(실험 E3).
- **크래시 복구**: 다시 실행했을 때 push하지 못한 변경이 남아 있으면, 그동안 아무도 락을 가져가지 않았을 때만 `main`에 이어서 올린다. 그 밖의 경우는 `recovery/<노드>-<epoch>-<커밋>` 브랜치에만 올려서 사람이 나중에 살펴볼 수 있게 한다.
- Paper 빌드는 `26.1.2-74`로 고정하고 월드 repo에 기록한다(`mcd-paper-build.txt`). 26.1+ 월드는 다운그레이드할 수 없어서 모든 호스트가 같은 빌드를 써야 한다.

## 테스트

- `./gradlew test`: 소유권 프로토콜(로컬 bare repo + 가짜 시계), push CAS 동작
- `./gradlew paperTest`: 실제 Paper와 Mineflayer 봇으로 E3 실험과 P0 시나리오 5개. `nodes/_cache/paper-26.1.2-74.jar`와 `e2e/`의 `npm install`이 필요하다.
- 실험 결과는 `experiments/`에 있다.

## 알려진 한계

- Paper는 stdin이 닫혀도 계속 돈다. 그래서 Paper JVM에 작은 에이전트(`ParentWatchdog`)를 붙여서, mcd가 죽으면 Paper도 저장하고 종료하게 했다. 다시 실행하면 그동안 아무도 락을 가져가지 않았을 경우 그 플레이 기록이 `main`에 이어진다(`McdCrashTest`). PC 전체가 꺼지면 TTL이 지난 뒤 다른 노드가 이어받는다.
- 호스트가 바뀌면 플레이어는 새 주소로 직접 다시 접속해야 한다.
- 아직 같은 PC나 같은 네트워크에서만 접속할 수 있다(인터넷 너머 접속은 M2).
- GitHub 이용 정책에는 과도한 자동 활동 금지 조항이 있다. 월드 저장용 계정이나 organization을 메인 계정과 분리하는 것을 권한다.
