// 테스트용 헤드리스 플레이어. offline mode 서버에 접속해서 stdin 명령을 처리하고, 결과를 한 줄씩 stdout에 쓴다.
// 사용법: node bot.js <host> <port> <username>
// 명령: "inventory" -> INVENTORY <아이템:개수,...>, "quit" -> 접속 종료
import mineflayer from 'mineflayer'
import readline from 'node:readline'

const [host, port, username] = process.argv.slice(2)
const bot = mineflayer.createBot({ host, port: Number(port), username, auth: 'offline' })

bot.once('spawn', () => console.log('SPAWNED'))
bot.on('kicked', (reason) => console.log('KICKED ' + JSON.stringify(reason)))
bot.on('error', (err) => console.log('ERROR ' + err.message))
bot.on('end', () => { console.log('ENDED'); process.exit(0) })

readline.createInterface({ input: process.stdin }).on('line', (line) => {
  const cmd = line.trim()
  if (cmd === 'inventory') {
    const items = bot.inventory.items().map((i) => `${i.name}:${i.count}`).sort()
    console.log('INVENTORY ' + items.join(','))
  } else if (cmd === 'quit') {
    bot.quit()
  }
})
