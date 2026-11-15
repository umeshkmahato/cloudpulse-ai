# Build
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY . .
RUN npm run build

# Serve: unprivileged nginx, same-origin reverse proxy to the services (replaces the Vite dev proxy)
FROM nginxinc/nginx-unprivileged:1.30.6-alpine
# Shrink the attack surface: the gateway loads no dynamic nginx modules and needs no outbound HTTP tooling.
USER root
RUN apk del --no-network nginx-module-xslt nginx-module-geoip nginx-module-image-filter nginx-module-njs libxslt curl libcurl
USER 101
COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY nginx/security-headers.conf /etc/nginx/security-headers.conf
COPY nginx/40-resolver.sh /docker-entrypoint.d/40-resolver.sh
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 8080
