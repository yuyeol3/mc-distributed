# E8: JDK AOT 캐시로 Paper 기동 줄이기

질문: Paper 기동(이관 시간 22초 중 10초, [E7](E7-results.md))의 대부분인 JVM과 마인크래프트 초기화를 JDK 25 AOT 캐시(JEP 483/514, `-XX:AOTCacheOutput` / `-XX:AOTCache`)로 줄일 수 있는가.

환경: 실제 월드 사본(`minecraft-distributed-world`, Paper 26.3-190), 배포판과 같은 jlink 런타임(Temurin 25.0.4), 플레이어 없음. 프로세스 시작부터 `Done (` 줄까지를 쟀다. (2026-10-17)

## 1. 방식 비교 (에이전트 없음, 번갈아 5회)

| 방식 | 중앙값 | 범위 |
|---|---|---|
| Paperclip (`-jar paper.jar`, 지금 방식) | 9.6초 | 8.7~10.5초 |
| 패치된 jar를 클래스패스로 직접 | 9.5초 | 9.2~10.2초 |
| Paperclip + AOT 캐시 | 6.0초 | 5.8~7.0초 |
| 직접 + AOT 캐시 | **4.1초** | 3.9~4.3초 |

- Paperclip은 서버를 자체 클래스 로더로 띄워서, AOT 캐시가 그 클래스들을 담지 못한다. 직접 띄워야 효과가 크다.
- 직접 띄우기: `org.bukkit.craftbukkit.Main`(패치된 jar의 Main-Class), 클래스패스는 Paperclip의 `META-INF/versions.list`와 `libraries.list`(해시, 아이디, 상대 경로)로 만든다(jar 104개).

## 2. mcd 조건으로 확인 (에이전트 포함, 번갈아 4회)

공용 폴더에 한 번 패치한 jar(절대 경로), 다른 폴더의 월드 사본에서 학습한 캐시, 실제 월드 폴더에서 실행, `ParentWatchdog` 에이전트 포함.

| 방식 | 4회 |
|---|---|
| Paperclip (지금 방식) | 11.1 / 14.5 / 11.2 / 15.4초 |
| 직접 + 사본에서 학습한 AOT 캐시 | 4.8 / 6.1 / 5.3 / 5.1초 |

## 알게 된 것

- **새 월드로 학습하면 캐시 조립이 실패한다.** `Cannot archive the sub-graph referenced from [Ljava.util.concurrent.ConcurrentHashMap$Node; ... size 262160`, `Child process failed`. 에이전트가 없어도 같았다. 이미 있는 월드(사본)로 학습하면 성공했다. 월드 생성 중에 만든 큰 정적 데이터를 담지 못하는 것으로 보인다.
- **에이전트(`-javaagent`)와 함께 학습하고 써도 된다.**
- **학습 실행은 느리다.** 기동 38초(평소의 약 4배)에 JVM이 끝난 뒤 자식 프로세스가 캐시를 조립하는 15초 안팎이 더 든다. 캐시는 193MB. 실제 호스트 기동에서 학습하면 안 되고 따로 해 둬야 한다.
- **캐시는 클래스패스의 jar가 같아야 쓰인다.** 그래서 jar를 월드마다 패치하지 않고 공용 폴더 하나에 두고 절대 경로로 가리켜야 학습한 곳과 실행하는 곳이 같아진다.
- 클래스패스 jar를 열지 못했을 때 JVM이 대체 경로 없이 시작을 거부했다(`An error has occurred while processing the AOT cache ... unrecoverable error`). 캐시가 맞지 않을 때의 동작은 구현 전에 따로 확인해야 한다.
- `@argfile` 안의 백슬래시는 이스케이프 문자라 Windows 경로는 슬래시(`C:/...`)로 쓴다.
- 캐시를 쓰면 경고 몇 줄(`Failed to link AdapterHandlerEntry ...`)이 콘솔에 나온다. 기동에는 영향이 없었다.

## 판정

Paper 기동을 6~9초 줄일 수 있다(이관 22초 → 15초 안팎 예상). 적용하려면 Paper 실행 방식을 바꿔야 한다: 공용 폴더에 빌드마다 한 번 패치, 직접 클래스패스로 실행, 월드 사본으로 백그라운드 학습, 캐시가 없거나 맞지 않으면 캐시 없이 실행.

## 한계

- 한 PC, 플레이어 없는 기동만 쟀다. 실제 이관에서의 효과는 다시 재야 한다.
- 2절의 Paperclip 값(11~15초)이 1절(9~10초)보다 크고 흔들렸다. 같은 시간대에 다른 작업이 있었을 수 있다. 같은 회차 안에서 번갈아 쟀으므로 비교는 유효하다고 본다.
