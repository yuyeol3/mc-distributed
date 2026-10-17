# E9: deploy key(SSH)로도 push가 락이 되는가

질문: GitHub 계정 없는 참가자를 위해 월드 repo별 deploy key로 SSH 접속하면, 소유권 프로토콜이 기대는 성질(E2: force 없는 동시 push는 정확히 하나만 성공)이 그대로인가. 배포판의 JGit과 런타임에서 SSH 접속이 되는가.

테스트: `PushCasTest.exactlyOneConcurrentPushWinsOnGitHubOverSsh` (2026-10-17)

## 조건

- 실험용 빈 private repo(`mcd-e9-ssh-test`). deploy key는 웹에서 쓰기 권한으로 등록했다.
- 키: `DeployKey`가 만든 ECDSA P-256. ed25519는 MINA sshd 2.x에서 추가 라이브러리가 필요해서 쓰지 않았다.
- 접속: `ssh://git@ssh.github.com:443/...`(방화벽이 22번을 막아도 되도록 443), JGit `org.eclipse.jgit.ssh.apache` 7.8.0(MINA sshd 2.19.0).
- `~/.ssh`(설정, 키, known_hosts)를 읽지 않는다. 서버 키는 GitHub가 공개한 SHA256 지문 세 개와만 비교한다.
- E2와 같은 방식: 회차마다 클라이언트 3개가 같은 부모에서 서로 다른 커밋을 동시에 push, 20회, 회차 사이 30초.

## 결과

| 항목 | 값 |
|---|---|
| 회차 | 20 |
| 회차마다 성공한 push | 모두 정확히 1개 |
| 진 쪽 거절 | 40건 모두 `REJECTED_OTHER_REASON cannot lock ref 'refs/heads/main': is at ... but expected ...` |
| push 시간(60건) | 중앙값 2.44초 (2.27~2.62초) |
| 이긴 push | 중앙값 2.35초 |

## 판정

- **SSH deploy key로도 push는 CAS로 동작한다.** 소유권 프로토콜을 바꾸지 않고 인증만 바꿀 수 있다.
- **push가 HTTPS(E2 중앙값 1.32초)보다 약 1.1초 느리다.** 명령마다 SSH 연결을 새로 맺기 때문으로 보인다. 락 획득과 heartbeat가 그만큼 길어진다. 이관 시간(E7)에서는 넘기는 쪽 반납 push와 받는 쪽 락 push에 더해진다. 필요해지면 연결 재사용을 검토한다.

## 함께 확인한 사실 (문서 조사)

- PAT로 등록한 deploy key는 그 PAT를 지우거나 철회하면 함께 삭제된다(GitHub REST 문서). 만료 때도 그런지는 문서에 없다. 그래서 키는 웹 화면에서 등록하기로 했다(웹에서 만든 키는 영향을 받지 않는다).
- deploy key는 repo 하나에만 쓸 수 있다. 여러 사람이 한 키를 함께 쓰는 것에 대한 약관 문구는 찾지 못했다(회색 지대, GitHub는 이런 용도로 machine user를 안내한다).
- 이번에 쓴 fine-grained PAT는 repo를 만들 권한이 없었다(`403 Resource not accessible by personal access token`).
