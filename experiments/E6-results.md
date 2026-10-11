# E6: 로컬 중계와 `/transfer`

테스트: `src/test/java/mcd/net/RelayTest.java`(중계 단위 테스트), `src/test/java/mcd/net/RelayTransferTest.java`(실제 Paper 2대 + 중계 + 봇)

## 확인한 것 (2026-10-11)

- **바이트를 그대로 넘기는 중계를 거쳐도 플레이가 정상**이다. 봇이 `localhost:<중계 포트>`로 접속해서 Paper A에 들어가고, 콘솔에서 준 아이템이 인벤토리에 나타났다.
- **`transfer localhost <중계 포트> <플레이어>`로 호스트를 옮길 수 있다.** 중계의 목적지를 B로 바꾼 뒤 A 콘솔에서 transfer를 보내면, 클라이언트가 *자기 PC의* localhost(중계)로 다시 접속하고 중계가 B로 이어준다. B는 `accepts-transfers=true`라서 transfer 접속을 받았다. 이 설정을 월드 템플릿에 넣었다.
- **Mineflayer 4.39.0은 서버 transfer 패킷을 처리하지 않는다**(`bot.transfer`는 인벤토리 기능). 26.1 프로토콜 데이터에는 transfer 패킷 정의가 있어서, `e2e/bot.js`가 패킷을 받으면 그 주소로 다시 접속하게 직접 구현했다.
- 테스트 수정: 아이템 지급은 서버 로그보다 늦게 클라이언트에 도착할 수 있어서, 인벤토리는 기대값이 될 때까지 기다려서 확인한다(`Bot.awaitInventory`). P0와 크래시 테스트도 같은 방식으로 바꿨다.

## 아직 확인하지 않은 것

- **online mode에서 진짜 클라이언트가 중계를 거쳐 접속하는 것.** 중계는 암호화된 바이트를 해석하지 않고 넘기므로 동작해야 하지만, 사용자 계정과 클라이언트로 한 번 확인해야 한다.
- 서버 목록의 상태 응답(ping)이 중계를 거쳐 정상으로 보이는지. 같은 TCP 연결이라 문제없을 것으로 보지만 확인하지 않았다.
