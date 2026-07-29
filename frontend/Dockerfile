FROM node:22-alpine AS build
ARG VITE_BASE_PATH=/
ARG VITE_API_BASE_URL=/api
ENV VITE_BASE_PATH=${VITE_BASE_PATH}
ENV VITE_API_BASE_URL=${VITE_API_BASE_URL}
WORKDIR /app
COPY package*.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npm run build \
    && npm sbom --sbom-format=cyclonedx > /tmp/frontend.cdx.json

FROM nginx:1.30.4-alpine3.24
ARG NGINX_CONFIG=nginx.conf
RUN apk upgrade --no-cache
COPY ${NGINX_CONFIG} /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
COPY --from=build /tmp/frontend.cdx.json /opt/sbom/frontend.cdx.json
RUN sed -i 's#^pid.*#pid /tmp/nginx.pid;#' /etc/nginx/nginx.conf \
    && chown -R nginx:nginx /var/cache/nginx /var/run /usr/share/nginx/html /opt/sbom
USER nginx
EXPOSE 8080
