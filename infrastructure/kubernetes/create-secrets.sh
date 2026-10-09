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
  $args
echo "devforge-ai-secrets created with random values"
