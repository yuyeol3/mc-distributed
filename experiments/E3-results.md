# E3: 실행 중인 서버에서 안전하게 스냅샷 뜨기

테스트: `src/test/java/mcd/server/LiveSnapshotTest.java` (`./gradlew paperTest --tests '*LiveSnapshotTest*'`)

절차: Paper 26.1.2-74 + Mineflayer 봇 1명(creative). 라운드마다 봇을 400블록씩 옮기고 유리 블록을 채운 뒤
`save-off` → `save-all flush` → 체크섬 → **봇을 200블록 더 옮겨 청크 언로드 유발** → 커밋+push → 5초 대기 → 체크섬 → `save-on`.
스냅샷 20개를 각각 체크아웃해서 Paper로 기동하고, 로그에 ERROR/Exception/corrupt가 없는지 본다.

판정 기준: 저장을 멈춘 동안 월드 파일이 바뀌지 않아야 하고(체크섬 일치), 모든 스냅샷이 에러 없이 기동돼야 한다.

## 1차 실행 (2026-10-11, Paper 기본 설정): 실패

- 실제로 확인한 로그 문구: `save-off` → `Automatic saving is now disabled`, `save-all flush` → `Saved the game`, `save-on` → `Automatic saving is now enabled`, 기동 완료 → `Done (…)! For help, type "help"`
- 19/20 라운드: 저장을 멈춘 동안 바뀐 파일 없음. 청크 언로드는 region 파일을 쓰지 않았다.
- **라운드 14: `level.dat`, `level.dat_old`, `data/minecraft/random_sequences.dat`, 차원별 `raids.dat`, `weather.dat`, `world_clocks.dat`, `paper/level_overrides.dat`가 바뀜.** region 파일은 바뀌지 않음. 서버 시작 후 5~6분 시점이라 Paper 자동 저장(기본 6000틱 = 5분)이 `save-off`와 상관없이 레벨 데이터를 쓴 것으로 추정.
- 스냅샷 20개 모두 에러 없이 기동됨.
- 대응: 월드 템플릿의 `bukkit.yml`에 `ticks-per.autosave: 0`을 넣어 자동 저장을 끄고, 저장은 mcd 주기 스냅샷이 맡는다.

## 2차 실행 (2026-10-11, 자동 저장 끔): 통과

- Paper가 기동 후에도 `bukkit.yml`의 `autosave: 0`을 유지함
- 20/20 라운드: 저장을 멈춘 동안 바뀐 파일 없음(약 11분 동안 실행했으므로 기본 자동 저장 주기 5분을 두 번 넘김). 1차의 라운드 14 원인이 자동 저장이라는 추정과 맞는다.
- 스냅샷 20개 모두 에러 없이 기동됨
- 테스트 코드의 판정 버그로 빌드는 실패로 표시됨(보고 줄에 `bukkit.yml` 확인 줄이 섞임). 고쳤음.
- 시간: `save-all flush` 150~600ms. **커밋+push는 월드가 커질수록 늘어남(파일 67개에서 3.5초 → 154개에서 12.4초)**, 이 시간 동안 저장이 멈춰 있었다.
- 대응: 저장을 멈춘 동안에는 로컬 커밋만 하고(`HostLock.commitWorld`), 저장을 다시 켠 뒤 push한다(`pushCommitted`). push는 이미 만든 git 객체를 보내므로 월드 파일이 고정될 필요가 없다.

## 3차 실행 (2026-10-11, 커밋과 push 분리): 통과

- 20/20 라운드 바뀐 파일 없음, 스냅샷 20개 모두 기동 성공
- 시간: flush 145~815ms, **로컬 커밋 2.3초 → 16.1초(월드 파일 65개 → 145개)**, push 0.8~3.9초
- 저장을 멈추는 시간은 이제 flush + 로컬 커밋이다. 병목은 push가 아니라 커밋이었다.

## 결론

- 스냅샷 절차: `save-off` → `save-all flush` → 로컬 커밋 → `save-on` → push. Paper 자동 저장은 끈다.
- 열린 문제: 로컬 커밋 시간이 월드 크기에 따라 빠르게 늘어난다. 저장을 멈춘 동안에도 게임은 진행되므로 당장 문제는 아니지만 큰 월드에서는 분 단위가 될 수 있다. 원인 후보는 (1) 이미 zlib으로 압축된 region 파일을 git이 다시 압축하는 비용, (2) flush가 로드된 청크를 모두 다시 써서 바뀌는 파일이 많아지는 것. E1(저장 형식)에서 측정해 확인한다.
