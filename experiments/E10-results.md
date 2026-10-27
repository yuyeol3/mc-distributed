# E10: 빠른 writer 인수와 월드·플러그인 관리

날짜: 2026-10-27. Windows 11, Java 25, 로컬 bare Git 저장소, 같은 PC의 별도 JVM 노드.
실제 VPN/SSH 원격 측정이나 절전·VM 정지 시험을 대신하지 않는다.

## 장애 인수

`FastFailoverTest`: A와 B의 TTL을 기본값인 600초로 둔다. A에서 플레이하고 B를 통해 A로 연결되는 것도 확인한 뒤 A 프로세스와 자식 Paper를 강제 종료한다. B에 새 로그인 요청을 보내 감지·경쟁 인수·30초 대기·Paper 시작·봇 spawn까지 측정한다.

- health: 5초 간격, 요청 제한 2초, 연속 실패 3회.
- 원격 소유권 확인: 5초 간격, 허가 최대 20초, 만료 후 종료 여유 5초.
- 인수 성공 후 대기: 30초.
- 최초 관찰 결과: `FAST_FAILOVER_READY_MS=74008`.
- 최종 코드 재검증 결과: `FAST_FAILOVER_READY_MS=56229`. 같은 600초 TTL이며, 이번 B 기동은 약 12초였다. 두 실행의 56~74초를 원격 환경의 보장 범위로 해석하지 않는다.
- 첫 호스팅 중 크래시하여 원격에는 아직 월드 스냅샷이 없는 조건이다. B의 새 월드 생성·기동 약 25초가 포함된다.
- 검증: 10분 TTL 전 실제 접속 완료, B의 다음 epoch, 조기 인수 로그.

`WriterGuardPaperTest`: A의 mcd 역할을 하는 테스트 JVM은 계속 살아 있는 상태에서 B가 다음 epoch를 push한다. A Paper가 독립 watchdog으로 종료되는지 확인한다.

- 최초 관찰 결과: `OLD_WRITER_STOP_MS=5727`.
- 최종 코드 재검증 결과: `OLD_WRITER_STOP_MS=2552`.
- 새 writer의 30초 대기 예산 안에 이전 Paper가 종료됐다.
- `ParentWatchdogTest`는 별도 JVM에서 부모가 살아 있어도 허가 서버가 사라지면 종료 코드 75로 끝나는 것을 확인한다.
- `ExecutionPermitTest`는 만료 뒤 늦게 도착한 조회 응답이 실행 허가를 되살리지 못하게 고정한다.

단일 로컬 측정으로 VPN 복구 시간의 상한이나 일반적인 성능 개선 배수를 주장하지 않는다. 전체 OS/VM 정지 때의 단일 Paper 보장도 주장하지 않는다.

## 월드·플러그인

`WorldAdminPaperTest`를 Paper 26.2-133으로 실행했다.

1. 실제 Paper로 월드를 생성하고 정상 종료한다.
2. 월드 가져오기와 플러그인 관리 작업으로 원격에 게시한다.
3. 실제 Paper API로 최소 JavaPlugin을 컴파일해 설치한다.
4. 다른 사본에서 `level.dat`와 JAR의 SHA-256 일치를 확인한다.
5. 해당 사본의 Paper 기동, `MCD_SAMPLE_ENABLED`, `plugins`의 실제 목록을 확인한다.

일반 테스트에는 관리 변경 실패 시 부분 파일 게시 방지, 백업 복구 시 Git 계보 보존, JAR 추가·제거의 다른 clone 반영, 누락 의존성, 로컬 전용·변조 JAR, ZIP 경로 이탈, 새 버전 거절, 실행 중인 원본 session lock 검사가 포함된다.

기존 `HandoffScenarioTest`, `McdCrashTest`도 실제 Paper 26.1.2-74와 Mineflayer로 통과했다.

## 실행 및 GUI

- 단위/통합: `./gradlew.bat test`.
- 전체 실행은 95개 중 실패 0, 환경 조건에 따른 건너뜀 3개였다. 이후 기존 버전 올리기 명령의 로컬 실행 잠금 회귀 테스트를 추가하고 해당 클래스 4개 테스트를 통과했다.
- 실행 폴더: `./gradlew.bat installDist` (`build/install/mcd`).
- Java 25 포함 배포판: `./gradlew.bat packageZip` 통과, `build/distributions/mcd-windows.zip`와 `.sha256` 생성. ZIP의 체크섬 일치를 확인했다.
- 빌드한 CLI의 `world backups`, `plugin list`를 격리된 로컬 repo에 실행해 종료 코드 0을 확인했다. 개발용 스크립트는 Java 25를 지정해야 한다(기본 JAVA_HOME의 Java 22에서는 class version 오류).
- 네트워크 helper: `go test ./...` 통과. 패키지에는 별도 Go 테스트 함수가 없다.
- GUI: `mcd.gui.WorldManagementPreview`의 격리된 사본을 `build/qa/world-management.png`로 렌더했다. 기본 창에서 여섯 버튼과 안내 문구의 잘림이 없다.
- Windows UI 자동화는 `Computer Use native pipe is unavailable ... os error 2`로 실패해 재시도·초기화 후 중단했다. 실제 클릭 흐름까지 검증했다고 표시하지 않는다.

PowerShell에서 Gradle JVM 속성은 `'-Dmcd.paperBuild=26.2-133'`처럼 인용한다.
