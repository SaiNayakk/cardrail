# The web app and the public edge: built once, served by nginx, which forwards the public APIs to the hosts.
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json .npmrc ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npx ng build --configuration production

FROM nginx:1.27-alpine
ENV ACQUIRING=acquiring NETWORK=network ISSUING=issuing MERIDIAN=network
COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist/cardrail-web/browser /usr/share/nginx/html
HEALTHCHECK --interval=10s --timeout=3s --retries=5 CMD wget -qO- http://127.0.0.1/healthz >/dev/null || exit 1
EXPOSE 80
