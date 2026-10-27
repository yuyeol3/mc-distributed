# 도커 실험실의 월드 원격(git daemon). alpine/git에는 git daemon이 없다.
FROM alpine:3.22
RUN apk add --no-cache git-daemon
ENTRYPOINT ["git", "daemon", "--reuseaddr", "--export-all", "--enable=receive-pack", "--base-path=/srv/git", "/srv/git"]
