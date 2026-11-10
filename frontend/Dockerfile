FROM node:20-alpine AS build
WORKDIR /app
# Install from the lockfile (npm ci), not a fresh resolve: package.json
# pins vitest at 2.1.2 while @vitest/coverage-v8 is a ^2.1.2 range, and
# a bare `npm install` re-resolves coverage-v8 to 2.1.9, whose peer is
# vitest@"2.1.9" — ERESOLVE, which failed `docker compose up --build`
# in CI (backlog #372). The lockfile pins the pair at 2.1.2, the same
# versions the CI frontend job installs, so the image build and the
# test job can no longer drift apart.
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
