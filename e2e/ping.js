// 서버 목록 확인(ping)을 한 번 보내고 결과를 한 줄로 출력한다. 사용법: node ping.js <host> <port>
import mc from 'minecraft-protocol'

const [host, port] = process.argv.slice(2)
try {
  const res = await mc.ping({ host, port: Number(port), closeTimeout: 20000 })
  console.log('PING ' + JSON.stringify({ description: res.description, version: res.version, players: res.players }))
} catch (err) {
  console.log('PINGERROR ' + err.message)
}
process.exit(0)
