# 도커 실험실의 PC 한 대. ./gradlew labImage가 build/lab-image에 빌드 재료를 모아 이 파일로 만든다.
FROM eclipse-temurin:25-jre
# 시나리오가 네트워크 장애(차단, 지연, 손실)를 걸고(NET_ADMIN 권한 필요) 프로세스를 확인하는 데 쓴다.
RUN apt-get update && apt-get install -y --no-install-recommends iptables iproute2 procps && rm -rf /var/lib/apt/lists/*
COPY lib/ /opt/mcd/lib/
COPY classes/ /opt/mcd/classes/
COPY cache/ /opt/mcd/cache/
COPY mcd-net start.sh /opt/mcd/
RUN chmod +x /opt/mcd/mcd-net /opt/mcd/start.sh
WORKDIR /opt/mcd
ENTRYPOINT ["/opt/mcd/start.sh"]
