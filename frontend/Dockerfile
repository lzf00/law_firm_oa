FROM node:22-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npm run build

FROM nginx:1.30.4-alpine3.24
ARG NGINX_CONFIG=nginx.conf
RUN apk upgrade --no-cache
COPY ${NGINX_CONFIG} /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
