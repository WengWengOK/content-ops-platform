# ── Content Ops 前端镜像（多阶段构建）──
# 构建上下文必须是仓库根目录：
#   docker build -f content-ops-frontend/Dockerfile -t content-ops-frontend .
FROM node:20-alpine AS build
WORKDIR /app

COPY content-ops-frontend/content-ops-frontend/package*.json ./
RUN npm ci
COPY content-ops-frontend/content-ops-frontend/ ./
RUN npm run build

FROM nginx:1.27-alpine
COPY content-ops-frontend/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
