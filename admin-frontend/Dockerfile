# Multi-stage build：与 frontend 风格一致，npm install + build + nginx 托管 dist
# 使用 node 20 是因为 mermaid 11.x 依赖项 (langium/chevrotain) 在 node 18 下会有 EBADENGINE 警告
FROM node:20-alpine AS builder
WORKDIR /app
COPY admin-frontend/package*.json ./
RUN npm install
COPY admin-frontend/ ./
RUN npm run build

# Nginx 运行阶段
FROM nginx:alpine
COPY --from=builder /app/dist /usr/share/nginx/html
COPY admin-frontend/nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
