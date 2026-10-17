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
| Paper | 월드가 빌드를 정함(새 월드는 26.2-133), 앱은 그 빌드를 받아 씀 | 한 월드의 호스트는 같은 빌드여야 함(다운그레이드 불가) |
| UI | MVP는 CLI, 배포 단계에서 Swing | JDK에 포함, 의존성 없음 |
| 테스트 플레이어 | Mineflayer 4.39.0 봇(offline mode) | 26.1까지 지원, 자동화 가능. 그 뒤 버전은 호환성 테스트와 정품 클라이언트로 확인 |

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
| 10. M3: 호스트 이관 | 다른 사람이 접속해 있는데 호스트가 나가면 마지막 스냅샷과 함께 락을 반납하고 곧바로 `transfer localhost 25565 @a`. 각자의 중계가 빈 락을 겨루고(한 노드만 이김), 진 쪽 중계는 새 Paper가 열릴 때까지 재시도. 기존 서버에서 바로 빠지므로 읽기 전용 구간이 필요 없음. 넘긴 뒤의 잔여 변경은 버려 recovery 브랜치가 생기지 않음. Windows에서 가상 스레드 소켓 중계가 가끔 멈추던 문제를 찾아 중계를 플랫폼 스레드로 바꿈 | `HandoffScenarioTest` |
| 11. 서버 버전 분리 | Paper 빌드를 앱 코드가 아니라 월드(`mcd-paper-build.txt`)가 정하게 함. Paper API에서 받아 SHA-256 검증 후 버전별 캐시. 지원 범위(1.20.5 이상, Java 요구 버전, 26.1.x 밖은 경고). `mcd upgrade-paper`로 락을 잡고 버전을 올림(다운그레이드와 호스트 중 변경은 거부) | `PaperDistributionTest`, `UpgradeCommandTest` |
| 12. 중계 첫머리 처리 | 서버 목록 확인은 호스트를 띄우지 않고 중계가 답하거나 살아 있는 호스트로 넘김. 로그인은 플러그인 질의로 붙잡아 두다가 첫머리를 다시 보내 이어줌. **정품 클라이언트로 35초 첫 기동을 끊김 없이 통과, 중계를 거친 정품 인증도 확인**. v0.1.0 릴리스 | `RelayTest`, `FirstJoinHoldTest` |
| 13. 자동 업데이트 | 앱 버전(manifest), 배포 zip의 `.sha256`. GitHub 릴리스에서 더 새 버전을 찾아 받고 검증한 뒤, PowerShell 스크립트가 설치 폴더의 mcd가 모두 끝나면 robocopy로 바꾸고 다시 켬. 배포판 0.2.0 → 가짜 릴리스 9.9.9로 실제 교체 확인 | `UpdaterTest`, `mcd.update` |
| 14. 새 월드 기본값 26.2 | 26.1.2 지원 종료(2026-07-26)로 새 월드를 26.2-133으로. `PaperCompatibilityTest`(봇 없이 콘솔 동작과 26.1.2 월드 업그레이드)와 정품 클라이언트 접속·플레이·호스트 이관(한 PC 노드 두 개, 35초)으로 확인. 이때 같은 빌드를 동시에 받으면 서로 덮어쓰던 다운로드 버그를 찾아 고침. 봇 E2E는 26.1.2 월드로 고정(`init --paper`) | `PaperCompatibilityTest` |
| 15. 켜 둔 노드의 버전 추적 | 노드가 시작할 때 읽은 Paper를 끝까지 써서, 켜 둔 동안 월드 버전이 올라가면 옛 Paper로 호스트하던 문제를 고침. 호스트가 될 때마다 월드의 빌드를 다시 읽음 | `WorldUpgradeWhileRunningTest` |
| 16. GUI 설정과 여러 월드 | 메인 창에서 월드 고르기·추가·목록에서 빼기·버전 올리기, 설정(이름, 토큰, 서버 주소) 변경. 설정 파일은 공통 설정과 월드 목록으로 바꾸고 옛 형식도 읽음. 노드 켜고 끄기는 한 스레드에서 차례로 | `SettingsTest`, 실제 창을 스크립트로 조작해 확인 |
| 17. 이관 시간 1차 단축 | Paper 로그로 기동 구간을 나눠 보니 월드 로딩은 1초 미만, 대부분은 JVM과 마인크래프트 초기화(부트스트랩 4~6초). 노드를 열 때 Paperclip 패치를 미리 해서(`paperclip.patchonly`, 처음 16.9초, 이후 0.6초) 처음 호스트가 되는 PC의 20초를 없앰. 접속 시 `peek` 없이 `tryAcquire` 한 번으로 fetch 한 번 절약. 락 획득, Paper 기동, 넘기기 전 저장과 push 시간을 로그에 남김. E2E(로컬 원격): 새 폴더 첫 접속 14초(전에는 약 35초), 이관은 저장과 push 1.1초, 락 획득 0.5초, 새 호스트 기동 10.0초 | `paperTest` 전체, `FirstJoinHoldTest` 출력 |

## 남은 단계 (이 순서로 진행)

## 다음에 할 것

- 월드에 "필요한 최소 mcd 버전"을 기록해, 옛 버전으로 그 월드를 열면 업데이트를 안내하기

- 실제 친구와 한 번 확인: 실제 원격 NAT에서 tailnet이 붙는지(정품 클라이언트의 중계 접속과 정품 인증은 확인함)
- 설치 파일(.msi): WiX Toolset 설치가 필요하다(사용자 결정)
- 호스트를 넘길 때 나가는 PC의 플레이어도 같이 끊긴다(그 PC의 사용자가 나가는 것이라 자연스럽지만, 한 PC에 여러 명이면 다름)

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
- 이관 시간: GitHub 원격에서 구간별 시간을 아직 재지 않았다(E2E는 로컬 원격). 재 보고 push/fetch가 크면 넘기기 전에 일반 스냅샷을 먼저 올려 release를 작게 만든다. Paper 부트스트랩(4~6초)은 JDK 25 AOT 캐시(`-XX:AOTCacheOutput`)로 줄일 수 있는지 실험한다(Paperclip의 자체 클래스 로더 때문에 패치된 jar를 직접 띄워야 할 수 있음). JVM을 미리 띄워 멈춰 두는 방식은 그 뒤에 검토한다
- 노드가 켜져 있는 동안 월드 버전이 올라가면 새 Paper는 미리 패치되지 않아 그 첫 기동이 느리다(E2E에서 22.3초)


- 로컬 작업 repo에 압축된 loose 객체가 쌓인다(스냅샷 20개에 340MB). 주기적인 로컬 gc가 필요하다.
- push 전송량이 스냅샷당 15~25MB(탐험 위주 세션 기준). 건축 위주 플레이에서 문제가 되면 저장 형식(E1: A vs C′)을 검토한다.

## 남은 불확실성

- VM 기반 검증이 실제 원격 환경을 얼마나 대신하는지: 실제 친구와 한 번 확인해야 한다

## 향후 아이디어: 월드 repo를 지도와 플레이 기록을 보여주는 페이지로

상태: 아이디어 기록(2026-10-11). 구현 일정과 공개 방식은 미정.

### 의도와 기능 후보

GitHub에 월드를 저장하는 특성을 살려, 월드 repo의 README에서 친구들이 함께 만든 월드를 구경하고 공유할 수 있게 한다. 지도 등을 생성하는 추가 도구나 플러그인을 제공하는 것을 차별화 후보로 검토한다.

- 최신 스냅샷의 월드 지도 이미지를 README에 표시
- 마지막 저장 날짜, 세션의 호스트, 원본 스냅샷 커밋 링크 표시
- 날짜별 지도 목록으로 월드가 변한 모습을 비교
- 친구들이 적는 건축물 소개, 좌표, 스크린샷과 플레이 기록

지도는 저장된 스냅샷 기준이다. 대화형 지도를 제공한다면 README에서는 해당 페이지로 연결하는 방식을 검토한다. 렌더러와 플러그인 구조는 기능의 유용성을 확인한 뒤 선택한다.

### 공개 범위와 인증 키 제약

현재 `mcd-network.properties`에는 친구가 clone만으로 tailnet에 붙도록 재사용 가능한 Tailscale 인증 키(`authKey`)가 들어 있다. 이는 기기를 등록하는 키이므로 유효한 키를 포함한 월드 repo를 공개할 수 없다.

| 공개 범위 | 후보 방식 | 고려할 점 |
|---|---|---|
| 친구들끼리 보기 | 기존 private 월드 repo의 README에 지도와 기록 추가 | 현재 접속·키 전달 방식을 유지할 수 있다 |
| 누구나 지도와 기록 보기 | 월드 repo는 private으로 유지하고, 생성한 지도와 README만 별도 public repo에 게시 | 공개 대상으로 선택한 결과물만 내보내며 인증 키와 운영 설정을 포함하지 않는다 |
| 월드 파일과 이력까지 공개 | 인증 키를 월드 repo 밖의 각 PC 로컬 설정에 저장하고 친구 초대 때 별도로 전달 | 친구의 첫 설정 부담이 늘 수 있다. 월드 데이터 자체의 공개 범위도 결정해야 한다 |

기존 월드 repo를 공개하려면 현재 파일 삭제만으로는 부족하다. 과거 커밋에도 키가 남아 있으므로 기존 키 폐기·교체와 이력 정리를 검토해야 한다. 참고: [Tailscale 인증 키](https://tailscale.com/docs/features/access-control/auth-keys), [GitHub 민감 정보 제거](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/removing-sensitive-data-from-a-repository).

### 나중에 검증할 최소 범위

먼저 private 월드 repo의 README에서 지도 한 장을 자동 갱신해 실제로 친구들이 자주 보고 유용하게 쓰는지 확인한다. 지도 생성은 확정된 스냅샷을 대상으로 하고, 렌더링 시간과 결과물 크기·전송량을 측정한다. 결과물을 월드 repo에 커밋한다면 호스트의 락·스냅샷 갱신과 충돌하지 않도록 게시 시점을 정해야 한다. 별도 public repo에 게시하는 방식은 월드 ref를 변경하지 않고 결과물을 공개할 수 있다.
