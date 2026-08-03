FROM node:20-alpine AS build
WORKDIR /app
COPY package.json ./
RUN npm install
COPY . .
# Build-Version (#626). Der Repo-Root (und damit die VERSION-Datei) liegt nicht im Build-Context
# (context: ./web), deshalb reicht CI Version + Commit als Build-Args durch; ohne sie baut ein
# lokales `docker build ./web` als 0.0.0-dev. Vite setzt die Werte fest ins Bundle ein.
ARG APP_VERSION=""
ARG GIT_SHA=""
ENV APP_VERSION=$APP_VERSION
ENV GIT_SHA=$GIT_SHA
RUN npm run build

# Unprivileged nginx (runs as uid 101, not root) to match the backend hardening
# (issue #61). It listens on 3000 (>1024), so binding needs no root; static
# assets copied in are world-readable, so the nginx user can serve them.
FROM nginxinc/nginx-unprivileged:alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx-spa.conf /etc/nginx/conf.d/default.conf
EXPOSE 3000
