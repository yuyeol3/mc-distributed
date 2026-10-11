# mcd 구현 계획

## 목표

**상시 서버 없이 친구들이 하나의 마인크래프트 월드를 계속 공유하는 시스템.** 플레이하려는 사람의 PC가 그때그때 서버가 되고, 월드는 git 원격 저장소(GitHub private repo)에 버전으로 보관한다. 최종적으로 **설치 파일 하나로 배포해서 친구들이 쉽게 쓸 수 있게** 한다.

CraftSync(OneDrive + Essential 모드)가 "번갈아 플레이"까지는 이미 한다. 차별점:
1. **사람이 개입하지 않아도 월드가 갈라지거나 손실되지 않음**: 크래시 자동 복구, 락을 잃은 호스트의 push 거부
2. **호스트가 나가도 남은 사람은 계속 플레이**: 호스트 이관
3. **사용자는 마크에서 `localhost`에 접속하기만 하면 됨**: 접속하는 사람이 필요하면 서버가 되고, 호스트가 바뀌면 자동으로 옮겨 감

진행 원칙: 불확실한 가정은 작은 실험으로 먼저 검증하고 판정 기준을 미리 정한다. 결과는 `experiments/*.md`에 수치로 남긴다. 단계가 끝나고 검증까지 마치면 커밋한다. **구조와 기능을 먼저 만들고 성능은 그다음 점진적으로 개선한다**(성능 문제는 열린 문제로 기록하고 기능 단계를 먼저 진행).

## 기술 선택

| 항목 | 선택 | 이유 |
|---|---|---|
| 언어/빌드 | Java 25, Gradle wrapper | Paper 생태계와 같음 |
| Git | JGit | 사용자가 git을 설치하지 않아도 됨 |
| 배포 | jpackage로 Java 25 런타임 포함 설치 파일 | Java 설치 불필요, 같은 런타임으로 Paper 실행 |
| GraalVM native-image | 하지 않음 | Paper가 JVM을 필요로 해서 JRE를 뺄 수 없음 |
| Paper | 26.1.2-74 고정, 월드 repo에 기록 | 26.1+ 월드는 다운그레이드 불가 |
| UI | MVP는 CLI, 배포 단계에서 Swing | JDK에 포함, 의존성 없음 |
| 테스트 플레이어 | Mineflayer 4.39.0 봇(offline mode) | 26.1까지 지원, 자동화 가능 |

## 완료된 단계

| 단계 | 결과 | 기록 |
|---|---|---|
| 0. 프로젝트 골격 | Gradle 9.8.1, JGit 7.8.0, JUnit 6.1.3 | |
| 1. E2: push를 락으로 | 로컬 100회, GitHub 20회 모두 승자 1명 | `experiments/E2-results.md` |
| 2. 소유권 프로토콜 | `main` 안의 `mcd-lock.properties` + force 없는 push. 자기 시계 lease, 자진 종료, recovery 브랜치 | `HostLock`, `HostLockTest` |
| 3. E3: 플레이 중 스냅샷 | Paper 자동 저장은 save-off 중에도 레벨 데이터를 씀 → 끔. 저장을 멈춘 동안 로컬 커밋만, push는 그 뒤 | `experiments/E3-results.md` |
| 4. MVP(M1) | `mcd init/play`, P0 시나리오 5개 E2E 통과. mcd가 죽으면 Paper도 종료(에이전트) | `P0ScenarioTest`, `McdCrashTest` |
| 5. E1′: 커밋 시간 원인 | JGit `add "."`이 바뀌지 않은 파일까지 다시 압축하던 것이 원인. 바뀐 파일만 stage해서 9.7초 → 1초 안팎(검증용 세션으로 확인). 저장 형식 변환은 불필요 | `experiments/E1-prime-results.md` |
| 6. E6: 중계와 transfer | 바이트 중계를 거쳐도 플레이 정상, `transfer localhost <중계 포트>`로 새 호스트로 이동 확인. Mineflayer는 transfer를 처리하지 않아 봇에 직접 구현. online mode 확인은 사용자에게 남김 | `experiments/E6-results.md` |
| 7. M2-a: 중계와 접속 시 호스트 | `mcd play`는 localhost:25565에 중계를 열고 기다리는 노드가 됨. 연결이 오면 살아 있는 호스트로 넘기거나 이 PC가 호스트가 됨. 빈 서버는 TTL/2(기본 5분) 뒤 스스로 반납. P0 시나리오 5개를 중계 방식으로 다시 통과 | `mcd.node`, `P0ScenarioTest` |
| 8. M2-b: 배포판, GUI, 마법사 | app-image zip(Java 런타임 포함), `mcd.exe`(GUI)와 `mcd-cli.exe`. 첫 실행 마법사(GitHub 토큰, 새 월드/참가, tailnet 키, EULA). 사용량 로그. 설치 파일(.msi)은 WiX 설치가 필요해 보류 | README |
| 9. E5: 인터넷 너머 연결 | tsnet 내장(`mcd-net`)으로 친구는 설치·로그인 없이 연결. VM(`mcd-friend`, base_machine 복제)과 Headscale로 호스트 ↔ VM 접속 확인. CPU 2개 PC에서 중계가 멈추던 가상 스레드 버그 수정 | `experiments/E5-results.md` |

## 남은 단계 (이 순서로 진행)

### 10. M3: `/transfer`를 이용한 호스트 이관
1. 기존 호스트: 공지 → 모두 adventure 모드 + `tick freeze`(월드 변경 차단) → 저장, 커밋 → 후임을 지정한 락(`owner=B, epoch+1`)과 월드를 커밋 하나로 push
2. 후임: pull → Paper 기동 → 준비 완료를 알림
3. 기존 호스트: `transfer localhost 25565 @a` → 각 클라이언트는 자기 mcd 중계를 거쳐 후임에게 접속 → 기존 호스트 종료
4. 후임: 게임 모드 복원, `tick unfreeze`
- 먼저 확인: adventure + tick freeze로 정말 월드 변경이 막히는지(인벤토리 등 새는 곳), 후임 선택 방법(중계에 연결된 노드 중에서), 준비 완료를 알리는 방법
- 완료 기준: A가 호스트이고 B, C가 접속 중일 때 A가 나가도 B와 C가 사람 손을 거치지 않고 같은 월드에서 계속 플레이
- 끊김이 거슬리면 E4(Velocity급 프록시, 연결 유지)를 검토

## 테스트

- `./gradlew test`: 소유권 프로토콜, push CAS
- `./gradlew paperTest`: 실제 Paper + Mineflayer 봇(E3, P0, mcd 크래시)
- 마일스톤 완료 기준은 E2E 테스트로 고정한다.

## GitHub 사용 위험

- push 권장 한도는 repo당 분당 6회, `.git` 10GB, 파일 하나 100MB(강제)다. 이용 정책에는 과도한 자동 활동 금지 조항이 있다.
- heartbeat 2분, 스냅샷 10분으로 push 빈도를 낮게 유지한다. 월드 저장용 계정을 분리하도록 안내한다.
- 원격은 git URL일 뿐이라 GitLab이나 자체 Gitea로 옮길 수 있다.

## 열린 성능 문제 (기능 단계 이후에 다룸)

- 계정 없는 직접 연결(UPnP, git 중개 hole punching)로 DERP 중계를 거치지 않게 하기

- Paper 기동이 25초를 넘으면(첫 기동은 라이브러리를 풀어서 느림) 중계가 연결을 닫아 플레이어가 다시 접속해야 한다. 중계가 로그인 단계에서 "기동 중" 안내를 보내는 방법을 검토한다.

- 로컬 작업 repo에 압축된 loose 객체가 쌓인다(스냅샷 20개에 340MB). 주기적인 로컬 gc가 필요하다.
- push 전송량이 스냅샷당 15~25MB(탐험 위주 세션 기준). 건축 위주 플레이에서 문제가 되면 저장 형식(E1: A vs C′)을 검토한다.

## 남은 불확실성

- 중계를 거친 online mode 동작(사용자가 진짜 클라이언트로 한 번 확인)
- VM 기반 검증이 실제 원격 환경을 얼마나 대신하는지: 실제 친구와 한 번 확인해야 한다
- 이관 중 읽기 전용 구간이 정말 새는 곳 없이 막히는지(10단계)
