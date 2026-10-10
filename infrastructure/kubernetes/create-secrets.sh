#!/bin/sh
# Creates devforge-ai-secrets with a fresh random value for every key - for a throwaway cluster
# (CI uses it). Not for production: nobody keeps these values, so nothing can be recovered or
# rotated deliberately. Use a secret manager there; see secrets.yaml.
set -eu

random() {
  # 32 random bytes, URL-safe, no padding: safe inside JAAS quotes and URLs alike.
  head -c 32 /dev/urandom | base64 | tr -d '\n=' | tr '+/' '-_'
}

args=""
for key in postgres-password jwt-signing-key metrics-token kafka-admin-password \
    kafka-password-auth-service kafka-password-project-service kafka-password-task-service \
    kafka-password-git-service kafka-password-review-service kafka-password-documentation-service \
    kafka-password-chat-service kafka-password-analytics-service kafka-password-notification-service \
    grafana-admin-password; do
  args="$args --from-literal=$key=$(random)"
done

# shellcheck disable=SC2086 - the values contain no spaces by construction.
kubectl create secret generic devforge-ai-secrets --namespace devforge-ai \
  --from-literal=postgres-username=devforge \
  --from-literal=mail-username= --from-literal=mail-password= \
  --from-literal=anthropic-api-key="${ANTHROPIC_API_KEY:-}" \
  $args
echo "devforge-ai-secrets created with random values"

# Kafka's TLS: a CA of this cluster's own signs the broker's certificate, and its private key is
# discarded once that is done. A real cluster would have cert-manager issue these instead.
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
store_password="$(random)"
openssl req -x509 -newkey rsa:3072 -nodes -keyout "$work/ca.key" -out "$work/ca.pem" -days 825 \
  -subj "/CN=DevForge Kafka CA" 2> /dev/null
openssl req -newkey rsa:3072 -nodes -keyout "$work/broker.key" -out "$work/broker.csr" \
  -subj "/CN=kafka" 2> /dev/null
printf 'subjectAltName=DNS:kafka,DNS:kafka.devforge-ai.svc,DNS:kafka.devforge-ai.svc.cluster.local,DNS:localhost,IP:127.0.0.1\n' \
  > "$work/san.ext"
openssl x509 -req -in "$work/broker.csr" -CA "$work/ca.pem" -CAkey "$work/ca.key" -CAcreateserial \
  -out "$work/broker.crt" -days 825 -extfile "$work/san.ext" 2> /dev/null
openssl pkcs12 -export -in "$work/broker.crt" -inkey "$work/broker.key" -certfile "$work/ca.pem" \
  -name broker -out "$work/broker.p12" -passout "pass:$store_password"
kubectl create secret generic kafka-tls --namespace devforge-ai \
  --from-file=ca.pem="$work/ca.pem" --from-file=broker.p12="$work/broker.p12" \
  --from-literal=store-password="$store_password"
echo "kafka-tls created with a fresh CA and broker certificate"
