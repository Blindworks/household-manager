FROM alpine:3.20

RUN apk add --no-cache docker-cli python3

COPY server.py /server.py

EXPOSE 8095

CMD ["python3", "/server.py"]
