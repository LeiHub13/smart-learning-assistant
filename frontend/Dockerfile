# 前端：node 构建 → nginx 托管
FROM node:20-alpine AS build
WORKDIR /build
COPY package.json package-lock.json ./
# npm ci 严格按 lock 安装（更快、版本确定）
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist /usr/share/nginx/html
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]