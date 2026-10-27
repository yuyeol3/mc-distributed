# 도커 실험실의 DERP 중계 서버. 직접 연결이 막힌 노드끼리 이 서버를 거쳐 통신한다(실제로는 NAT 뒤의 PC들).
# net-helper가 쓰는 tsnet과 같은 tailscale 버전으로 맞춘다.
FROM golang:1.27-alpine AS build
RUN CGO_ENABLED=0 go install tailscale.com/cmd/derper@v1.102.5

FROM alpine:3.22
COPY --from=build /go/bin/derper /usr/local/bin/derper
ENTRYPOINT ["derper"]
