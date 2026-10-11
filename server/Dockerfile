FROM node:22-alpine
WORKDIR /app
COPY schedule-service.cjs playback-profiles.cjs ./
RUN mkdir /data && chown node:node /data
USER node
VOLUME /data
EXPOSE 8787
CMD ["node", "schedule-service.cjs"]
