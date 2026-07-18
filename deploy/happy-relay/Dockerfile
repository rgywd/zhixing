FROM node:20-slim@sha256:2cf067cfed83d5ea958367df9f966191a942351a2df77d6f0193e162b5febfc0

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl ffmpeg \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /opt/happy

COPY package.json package-lock.json ./

RUN npm ci --omit=dev

COPY patch-pglite-bytes.mjs ./
RUN node patch-pglite-bytes.mjs

ENV NODE_ENV=production \
    DATA_DIR=/data \
    PGLITE_DIR=/data/pglite \
    PORT=3005

WORKDIR /data

VOLUME ["/data"]
EXPOSE 3005

CMD ["sh", "-c", "/opt/happy/node_modules/.bin/happy-server migrate && exec /opt/happy/node_modules/.bin/happy-server serve"]
