# Token-refresher image (backlog #430): alpine + curl + jq only, runs
# refresh-token.sh as PID 1. Pinned like the other images in this repo
# (backend/Dockerfile pins its base images); alpine:3.20 verified on
# Docker Hub 2026-11-23.
FROM alpine:3.20
RUN apk add --no-cache curl jq
COPY refresh-token.sh /usr/local/bin/refresh-token.sh
RUN chmod +x /usr/local/bin/refresh-token.sh
ENTRYPOINT ["/usr/local/bin/refresh-token.sh"]
