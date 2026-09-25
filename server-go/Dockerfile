FROM golang:1.27-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY *.go ./
RUN CGO_ENABLED=0 go build -o /out/diagnostic-server .

FROM alpine:3.20
WORKDIR /app
COPY --from=build /out/diagnostic-server ./
ENV PORT=44096
ENV DATA_DIR=/data
ENV LOG_FILE=/data/server.log
EXPOSE 44096
VOLUME ["/data"]
CMD ["/app/diagnostic-server"]
