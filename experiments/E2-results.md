# E2: force 없는 push를 락(CAS)으로 쓸 수 있는가

테스트: `src/test/java/mcd/lock/PushCasTest.java`

판정 기준: 매 회차 push에 성공한 클라이언트가 정확히 하나이고, 원격 `main`이 승자의 커밋과 같아야 한다.

## 로컬 bare repo (2026-10-11)

- 조건: JGit 7.8.0, Windows 11, 같은 JVM 안의 스레드 4개가 `CyclicBarrier`로 동시에 push, 100회
- 결과: **통과**. 100회 모두 성공 1개, 원격 `main` = 승자 커밋
- 패자 299건 모두 `REJECTED_OTHER_REASON failed to lock`. 동시에 들어오면 non-fast-forward가 아니라 ref 잠금 단계에서 거절된다. 그래서 프로토콜은 패자의 거절 사유를 구분하지 않고 "OK가 아니면 실패"로만 판단해야 한다.
- push 소요 시간(400건): 중앙값 185ms, 최대 474ms

## GitHub (2026-10-11)

- 조건: private repo `yuyeol3/mcd-e2-test`, HTTPS + 토큰, 스레드 3개 동시 push, 20회, 회차 사이 30초
- 결과: **통과**. 20회 모두 성공 1개, 원격 `main` = 승자 커밋
- 패자 40건 모두 `REJECTED_OTHER_REASON cannot lock ref 'refs/heads/main': is at <승자> but expected <부모>`. GitHub 서버가 기대한 이전 값과 현재 값을 비교해서 거절한다. 문구는 로컬과 다르지만 CAS로 동작하는 건 같다.
- push 소요 시간(60건): 중앙값 1.32초, 최대 1.51초. heartbeat 간격(분 단위)에 비하면 무시할 수준
- 한계: 회차당 push 3회 + clone 3회로, 권장 한도(분당 push 6회)에 걸친 부하였음. 장시간 heartbeat 측정은 하지 않음(MVP에서 실제 사용량으로 측정)

## 결론

force 없는 push를 락으로 쓰는 설계를 확정한다. 프로토콜은 push 결과가 `OK`인지만 보고, 거절 사유 문구에 의존하지 않는다.
