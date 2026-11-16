FROM node:22-alpine AS build
WORKDIR /src
COPY package.json ./
RUN npm install
COPY . .
COPY hashes-build.mjs /src/hashes-build.mjs
ARG VITE_TURN_USER=bleep
ARG VITE_TURN_PASS=bleep-dev
ENV VITE_TURN_USER=$VITE_TURN_USER \
    VITE_TURN_PASS=$VITE_TURN_PASS
RUN npm run build && node hashes-build.mjs

FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/dist /usr/share/nginx/html
EXPOSE 80
