#!/bin/sh
# Generates the broker's TLS certificate on a stack's first start, then exits.
#
# A CA of this stack's own signs a certificate for "kafka" (the name services connect to) and
# "localhost" (the broker's own health check and the host). The CA's public certificate goes to
# /certs/ca.pem, which every client trusts; its private key is discarded once the broker
# certificate is signed, so nothing can mint another certificate this stack would accept.
#
# Every stack gets its own: there is no shared or committed private key. Delete the certificate
# volume to start over.
set -eu

DIR=/certs
if [ -f "$DIR/broker.p12" ] && [ -f "$DIR/ca.pem" ]; then
  echo "certificates exist"
  exit 0
fi

PASSWORD="${KAFKA_TLS_STORE_PASSWORD:?KAFKA_TLS_STORE_PASSWORD is required}"
# The JRE's keytool, which the Kafka image ships but does not always put on PATH.
if ! command -v keytool > /dev/null 2>&1; then
  JAVA_BIN="$(dirname "$(readlink -f "$(command -v java)")")"
  PATH="$JAVA_BIN:$PATH"
fi
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

keytool -genkeypair -alias ca -keyalg RSA -keysize 3072 -dname "CN=DevForge Kafka CA" -ext bc:c \
  -validity 825 -keystore "$WORK/ca.p12" -storetype PKCS12 -storepass "$PASSWORD" -noprompt
keytool -exportcert -alias ca -keystore "$WORK/ca.p12" -storepass "$PASSWORD" -rfc -file "$WORK/ca.pem"

keytool -genkeypair -alias broker -keyalg RSA -keysize 3072 -dname "CN=kafka" -validity 825 \
  -keystore "$WORK/broker.p12" -storetype PKCS12 -storepass "$PASSWORD" -keypass "$PASSWORD" -noprompt
keytool -certreq -alias broker -keystore "$WORK/broker.p12" -storepass "$PASSWORD" -file "$WORK/broker.csr"
keytool -gencert -alias ca -keystore "$WORK/ca.p12" -storepass "$PASSWORD" -infile "$WORK/broker.csr" \
  -outfile "$WORK/broker.crt" -ext san=dns:kafka,dns:localhost,ip:127.0.0.1 -validity 825 -rfc
keytool -importcert -alias ca -file "$WORK/ca.pem" -keystore "$WORK/broker.p12" -storepass "$PASSWORD" -noprompt
keytool -importcert -alias broker -file "$WORK/broker.crt" -keystore "$WORK/broker.p12" -storepass "$PASSWORD" -noprompt

# The CA certificate is public. The keystore holds the broker's private key: readable by the
# broker's user only (uid 1000 in the Kafka image), as well as by its password.
cp "$WORK/ca.pem" "$DIR/ca.pem"
cp "$WORK/broker.p12" "$DIR/broker.p12"
chmod 644 "$DIR/ca.pem"
chown 1000 "$DIR/broker.p12"
chmod 600 "$DIR/broker.p12"
echo "generated a CA and a broker certificate for kafka, localhost"
