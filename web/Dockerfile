FROM node:22-bookworm-slim

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates ffmpeg python3 tini \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY web/package.json ./package.json
COPY web/server ./server
COPY web/public ./public
COPY library/src/main/res/raw/ytdlp /opt/the-hand/yt-dlp

RUN chmod 0755 /opt/the-hand/yt-dlp \
    && mkdir -p /data \
    && chown -R node:node /app /data

ENV HOST=0.0.0.0 \
    PORT=8787 \
    DATA_DIR=/data \
    YTDLP_PATH=/opt/the-hand/yt-dlp

USER node
EXPOSE 8787
ENTRYPOINT ["/usr/bin/tini", "--"]
CMD ["node", "server/server.mjs"]
