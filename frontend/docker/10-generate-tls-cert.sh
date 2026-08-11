#!/bin/sh
# Erzeugt beim ersten Container-Start eine eigene CA plus Server-Zertifikat
# fuer HTTPS im LAN. Eine CA ist noetig, weil Android/iOS nur CA-Zertifikate
# als vertrauenswuerdig installieren koennen — ein nacktes selbstsigniertes
# Server-Zertifikat liesse sich auf den Handys nicht importieren, und ohne
# vertrauenswuerdiges Zertifikat registriert der Browser keinen Service
# Worker (= keine PWA-Installation).
#
# Das Verzeichnis ist per docker-compose auf ./certs gemountet: ca.crt dort
# einmalig auf jedem Geraet installieren. CA und Zertifikat bleiben ueber
# Neustarts erhalten. Aendern sich die SANs (TLS_SANS), server.crt und
# server.key loeschen — beim naechsten Start wird mit derselben CA neu
# ausgestellt, die Geraete-Installation bleibt gueltig.
set -eu

CERT_DIR=/etc/nginx/certs
SANS="${TLS_SANS:-IP:192.168.1.100,DNS:localhost}"

if [ -f "$CERT_DIR/server.crt" ]; then
  exit 0
fi

mkdir -p "$CERT_DIR"

if [ ! -f "$CERT_DIR/ca.crt" ]; then
  echo "Erzeuge Household-Manager-CA (certs/ca.crt auf den Geraeten installieren)"
  openssl req -x509 -newkey rsa:2048 -nodes -days 3650 \
    -keyout "$CERT_DIR/ca.key" -out "$CERT_DIR/ca.crt" \
    -subj "/CN=Household Manager CA"
fi

echo "Stelle Server-Zertifikat aus (SANs: $SANS)"
openssl req -newkey rsa:2048 -nodes \
  -keyout "$CERT_DIR/server.key" -out /tmp/server.csr \
  -subj "/CN=Household Manager"
printf 'subjectAltName=%s\n' "$SANS" > /tmp/san.cnf
# 825 Tage ist die laengste Laufzeit, die iOS fuer manuell vertraute
# Zertifikate akzeptiert
openssl x509 -req -in /tmp/server.csr -days 825 \
  -CA "$CERT_DIR/ca.crt" -CAkey "$CERT_DIR/ca.key" -CAcreateserial \
  -extfile /tmp/san.cnf -out "$CERT_DIR/server.crt"
rm -f /tmp/server.csr /tmp/san.cnf
