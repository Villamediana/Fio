# O servidor do Novelo: Node puro (node:sqlite vem junto), sem dependências pra instalar.
FROM node:24-slim
ENV NODE_ENV=production
WORKDIR /app
COPY package.json ./
COPY src ./src
COPY migrations ./migrations
COPY tools ./tools
RUN mkdir -p /app/data && chown -R node:node /app
USER node
ENV DB_PATH=/app/data/novelo.db PORT=8787 HOST=0.0.0.0
EXPOSE 8787
HEALTHCHECK --interval=30s --timeout=5s --retries=3 CMD node -e "fetch('http://127.0.0.1:8787/health').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"
CMD ["node", "--no-warnings", "src/main.js"]
