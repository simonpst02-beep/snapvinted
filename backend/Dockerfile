FROM node:22-alpine

WORKDIR /app

COPY package*.json ./
RUN npm ci --omit=dev

COPY *.mjs ./

EXPOSE 8103

CMD ["node", "server.mjs"]
