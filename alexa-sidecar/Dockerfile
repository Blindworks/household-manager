FROM node:20-alpine

WORKDIR /app

# Install dependencies first for better layer caching.
COPY package.json ./
RUN npm install --omit=dev

# Copy the application source.
COPY server.js smarthome.js ./

# Persisted cookie/registration lives here; mount a volume to survive restarts.
ENV COOKIE_PATH=/app/data/alexa-cookie.json
RUN mkdir -p /app/data

# HTTP API port and browser-login proxy port.
ENV PORT=3456
ENV PROXY_PORT=3457
EXPOSE 3456
EXPOSE 3457

CMD ["node", "server.js"]
