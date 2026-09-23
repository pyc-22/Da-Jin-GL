ARG NODE_IMAGE=m.daocloud.io/docker.io/library/node:22-alpine
ARG NGINX_IMAGE=m.daocloud.io/docker.io/library/nginx:alpine
FROM ${NODE_IMAGE} AS build
WORKDIR /app
COPY package.json pnpm-lock.yaml ./
RUN npm install -g pnpm@9.15.4 && pnpm install --frozen-lockfile
COPY . .
RUN pnpm run build

FROM ${NGINX_IMAGE}
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
HEALTHCHECK --interval=15s --timeout=5s --retries=10 CMD wget -q -O /dev/null http://localhost/ || exit 1
