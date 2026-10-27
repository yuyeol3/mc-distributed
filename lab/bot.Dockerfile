# 도커 실험실의 플레이어(e2e/bot.js). 빌드 문맥은 e2e/다. node_modules를 Windows 폴더에서 바인드 마운트로 읽으면
# 봇 하나 띄우는 데 25초 넘게 걸려서 이미지에 넣는다.
FROM node:22-slim
COPY . /e2e/
ENTRYPOINT ["node", "/e2e/bot.js"]
