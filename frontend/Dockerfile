# One image per app, built from the repository root (the site renders docs/api/openapi.yaml, which lives outside frontend/):
#   docker build -f frontend/Dockerfile --build-arg APP=checkout -t cutpay/checkout .
# The build output is static files; nginx serves them, proxies the API paths to the gateway and sends the security
# headers (docs/security/threat-model.md): the dev server's header plugin does not exist in production.

FROM node:22-alpine AS build
ARG APP
RUN corepack enable
WORKDIR /src
COPY frontend frontend
COPY docs/api docs/api
WORKDIR /src/frontend
RUN test -n "$APP" || (echo "pass --build-arg APP=<checkout|dashboard|backoffice|site>" && exit 1)
RUN --mount=type=cache,target=/root/.local/share/pnpm/store pnpm install --frozen-lockfile
RUN pnpm --filter "@cutpay/${APP}" build

FROM nginx:1.29-alpine
ARG APP
COPY --from=build /src/frontend/apps/${APP}/dist /usr/share/nginx/html
COPY frontend/deploy/nginx/security-headers.conf /etc/nginx/snippets/security-headers.conf
COPY frontend/deploy/nginx/${APP}.conf.template /etc/nginx/templates/default.conf.template
# GATEWAY_URL: where the API paths go. DNS_RESOLVER: nginx resolves it at request time, so the page serves even
# while the gateway is away; in Kubernetes set it to the cluster DNS service.
ENV GATEWAY_URL=http://gateway:8080 \
    DNS_RESOLVER=127.0.0.11
EXPOSE 8080
