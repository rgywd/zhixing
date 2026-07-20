FROM node:24-alpine

WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev
COPY core ./core
RUN mkdir -p /data && chown -R node:node /app /data

USER node
ENV HOST=0.0.0.0 PORT=8787 WORK_CORE_DB=/data/work-core.sqlite
EXPOSE 8787
VOLUME ["/data"]
HEALTHCHECK --interval=30s --timeout=5s --retries=3 CMD node -e "fetch('http://127.0.0.1:8787/healthz').then(r=>{if(!r.ok)process.exit(1)}).catch(()=>process.exit(1))"
CMD ["node", "core/index.js"]
