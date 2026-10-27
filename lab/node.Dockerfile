# 도커 실험실의 PC 한 대. ./gradlew labImage가 build/lab-image에 빌드 재료를 모아 이 파일로 만든다.
FROM eclipse-temurin:25-jre
COPY lib/ /opt/mcd/lib/
COPY classes/ /opt/mcd/classes/
COPY cache/ /opt/mcd/cache/
COPY mcd-net /opt/mcd/mcd-net
RUN chmod +x /opt/mcd/mcd-net
WORKDIR /opt/mcd
ENTRYPOINT ["java", "-Dmcd.netHelper=/opt/mcd/mcd-net", "-cp", "/opt/mcd/classes:/opt/mcd/lib/*", "mcd.cli.Main"]
