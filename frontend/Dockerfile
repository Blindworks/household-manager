FROM node:20-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:1.27-alpine
# openssl fuer die TLS-Zertifikat-Erzeugung beim ersten Start
RUN apk add --no-cache openssl
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY docker/10-generate-tls-cert.sh /docker-entrypoint.d/10-generate-tls-cert.sh
RUN chmod +x /docker-entrypoint.d/10-generate-tls-cert.sh
COPY --from=build /app/dist/household-manager/browser /usr/share/nginx/html
EXPOSE 80 443
