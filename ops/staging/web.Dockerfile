# Next standalone and static assets must be produced by the verified host/CI build.
FROM node:24-bookworm-slim
WORKDIR /app
ENV NODE_ENV=production NEXT_TELEMETRY_DISABLED=1 HOSTNAME=0.0.0.0 PORT=3000
COPY --chown=node:node frontend/.next/standalone ./
COPY --chown=node:node frontend/.next/static ./.next/static
COPY --chown=node:node frontend/public ./public
USER node
EXPOSE 3000
CMD ["node","server.js"]
