FROM node:20-slim

# 安装 Chromium 运行依赖并下载 Playwright 自带浏览器（镜像内置，无需宿主安装）
RUN npx playwright install --with-deps chromium

WORKDIR /app
COPY package.json ./
RUN npm install --omit=dev

COPY server.js ./

ENV PORT=3000
EXPOSE 3000

CMD ["node", "server.js"]
