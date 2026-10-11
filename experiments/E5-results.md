# E5: 인터넷 너머 친구와 연결하기 (tailnet 내장)

## 방식 선택

| 후보 | 친구가 할 일 | 월드 주인이 할 일 | 판단 |
|---|---|---|---|
| Tailscale 별도 설치 | 설치 + 로그인 | 설치 + 로그인 | 번거로워서 제외 |
| UPnP 자동 포트 열기 | 없음 | 없음 | 공유기와 통신사(CGNAT)에 따라 안 되는 집이 있다. 나중에 최적화로 추가 |
| **tsnet 내장(채택)** | **없음** | 재사용 가능한 인증 키를 한 번 발급해 월드에 기록 | NAT와 상관없이 연결(안 되면 DERP 중계) |
| 오픈소스 VPN(WireGuard, Nebula, Headscale 등) | - | 공인 IP가 있는 중개 노드나 조정 서버를 직접 운영 | "상시 서버 없음"과 맞지 않음 |
| git을 중개자로 쓰는 UDP hole punching | 없음 | 없음 | 일부 NAT에서 실패하고 대신 중계할 서버가 없다. 구현량도 큼. 나중에 실험 후보 |

구조: `net-helper/`(Go, tailscale.com/tsnet v1.102.5)로 만든 `mcd-net`을 mcd가 자식 프로세스로 띄운다. 나가는 연결은 `mcd-net`의 SOCKS5로, 들어오는 연결은 tailnet의 Paper 포트에서 로컬 Paper로 넘어온다. 월드 repo의 `mcd-network.properties`(조정 서버 주소, 인증 키)가 있으면 켜진다. 노드 키는 월드 폴더 밖(`<cache>/../tailnet/`)에 저장한다.

## 시험 환경 (2026-10-11)

- "친구 PC": 사용자의 기존 VM `base_machine`에서 linked clone한 `mcd-friend`(Ubuntu 24.04, CPU 2개, RAM 2GB, VirtualBox NAT). 원본은 건드리지 않았다.
- 조정 서버: VM 안의 Headscale v0.29.4(테스트용, 계정 불필요). DERP는 Headscale 기본 설정(Tailscale 공개 DERP 맵).
- 월드 원격: 호스트의 `git daemon`(VM에서는 NAT 게이트웨이 `10.0.2.2:9418`로 접근)
- VM에는 Temurin 25 JRE, Node.js 22.23.2, mcd jar, Linux용 `mcd-net`만 두었다(체크섬 확인).

## 결과

1. **보조 프로그램만으로**: VM이 자기 `mcd-net`의 SOCKS5를 거쳐 호스트의 tailnet IP로 HTTP 요청 → 0.17초에 응답. 연결 6ms.
2. **호스트 안의 두 노드(`TailnetScenarioTest`)**: A와 B가 각각 tailnet 주소를 받았고, B의 중계로 들어온 bob이 tailnet을 거쳐 A의 Paper에 접속했다. 통과.
3. **호스트 ↔ VM**: 호스트 노드 A가 호스트가 되고(alice 접속), VM의 bob이 VM의 `localhost:25565`로 접속하자 VM 노드의 중계가 tailnet으로 A의 Paper에 이어줬다(`bob joined the game`). VM에는 Tailscale을 설치하지도 로그인하지도 않았다.

## 시험 중 발견해서 고친 것

- **CPU 2개인 PC에서 중계가 멈추는 버그.** 프로세스 출력과 stdin 읽기를 가상 스레드에서 했는데, 이것은 파일 I/O라 가상 스레드가 캐리어 스레드를 붙잡는다. VM의 캐리어 2개가 `mcd-net` 출력 읽기와 콘솔 읽기에 묶여서 중계의 accept가 전혀 돌지 않았다(스레드 덤프로 확인). 호스트 PC는 코어가 많아서 드러나지 않았다. 오래 막히는 스트림 읽기는 플랫폼 데몬 스레드로 바꿨다.
- 봇 스크립트가 스폰 전에 `quit`을 받으면 오류를 내던 것.

## 한계와 남은 것

- 새 폴더의 첫 Paper 기동(mojang jar 받기, 패치)이 25초를 넘으면 중계가 연결을 닫아 첫 접속이 실패한다. 다시 접속하면 된다. 로그인 단계에서 "기동 중" 안내를 보내는 개선이 필요하다.
- VM은 RAM 2GB(호스트 여유 메모리 부족으로 늘리지 않음)라 VM이 호스트가 되는 경우는 시험하지 않았다.
- 같은 PC 안의 VM이라 실제 원격 친구의 NAT 조건(직접 연결 실패 → DERP 중계)과 다르다. 최종 확인은 실제 친구와 한다.
- tailnet으로 들어온 플레이어는 Paper에 127.0.0.1로 보인다(보조 프로그램이 로컬로 넘겨서). IP 차단 같은 기능은 쓸 수 없다.
- 인증 키가 private 월드 repo의 collaborator에게 보인다. 키는 재사용 가능하되 tailnet 권한을 좁게(태그, ACL) 발급하도록 안내해야 한다.
