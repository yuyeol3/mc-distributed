// 테스트용 헤드리스 플레이어. offline mode 서버에 접속해서 stdin 명령을 처리하고, 결과를 한 줄씩 stdout에 쓴다.
// 사용법: node bot.js <host> <port> <username>
// 명령: "inventory" -> INVENTORY <아이템:개수,...>, "quit" -> 접속 종료
// 서버가 transfer 패킷(1.20.5+)을 보내면 실제 클라이언트처럼 그 주소로 다시 접속한다. Mineflayer는 이 패킷을 처리하지 않아서 직접 구현했다.
import mineflayer from 'mineflayer'
import readline from 'node:readline'

const [host, port, username] = process.argv.slice(2)
let bot

function connect (targetHost, targetPort) {
  let transferredAway = false
  bot = mineflayer.createBot({ host: targetHost, port: Number(targetPort), username, auth: 'offline' })
  bot.once('spawn', () => console.log('SPAWNED ' + targetHost + ':' + targetPort))
  bot.on('kicked', (reason) => console.log('KICKED ' + JSON.stringify(reason)))
  bot.on('error', (err) => console.log('ERROR ' + err.message))
  bot.on('end', () => {
    if (transferredAway) return
    console.log('ENDED')
    process.exit(0)
  })
  bot._client.on('transfer', (packet) => {
    console.log('TRANSFER ' + packet.host + ':' + packet.port)
    transferredAway = true
    bot.quit()
    setTimeout(() => connect(packet.host, packet.port), 500)
  })
}

connect(host, port)

readline.createInterface({ input: process.stdin }).on('line', (line) => {
  const cmd = line.trim()
  if (cmd === 'inventory') {
    const items = bot.inventory.items().map((i) => `${i.name}:${i.count}`).sort()
    console.log('INVENTORY ' + items.join(','))
  } else if (cmd === 'quit') {
    bot.quit()
  }
})
