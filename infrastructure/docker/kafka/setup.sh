#!/bin/sh
# Creates the topics and grants each service exactly the access it needs, then exits.
#
# The broker denies anything without an ACL (allow.everyone.if.no.acl.found=false), so this file is
# the complete statement of who may read and write what. Before it, any service holding the one
# shared credential could read every tenant's events and forge events onto any topic, which every
# consumer accepts as fact.
#
# Runs as the broker's super user, from the one-shot kafka-setup compose service.
set -eu

BOOTSTRAP="kafka:29092"
CONFIG=/tmp/admin.properties
cat > "$CONFIG" <<EOF
security.protocol=SASL_PLAINTEXT
sasl.mechanism=PLAIN
sasl.jaas.config=org.apache.kafka.common.security.plain.PlainLoginModule required username="admin" password="${KAFKA_ADMIN_PASSWORD}";
EOF

topic() {
  kafka-topics --bootstrap-server "$BOOTSTRAP" --command-config "$CONFIG" \
    --create --if-not-exists --topic "$1" --partitions 3 --replication-factor 1
}

acl() {
  kafka-acls --bootstrap-server "$BOOTSTRAP" --command-config "$CONFIG" --add "$@" > /dev/null
}

# Producers write their own topics only.
produces() {
  service="$1"; shift
  for t in "$@"; do acl --allow-principal "User:$service" --operation Write --operation Describe --topic "$t"; done
}

# Consumers read their topics in their own group, and write those topics' dead-letter copies.
consumes() {
  service="$1"; shift
  acl --allow-principal "User:$service" --operation Read --group "$service"
  for t in "$@"; do
    acl --allow-principal "User:$service" --operation Read --operation Describe --topic "$t"
    acl --allow-principal "User:$service" --operation Write --operation Describe --topic "$t.dlt"
  done
}

for t in devforge.identity.v1 devforge.security.v1 devforge.tasks.v1 devforge.repositories.v1 devforge.projects.v1; do
  topic "$t"
  topic "$t.dlt"
done

produces auth-service devforge.identity.v1 devforge.security.v1
produces project-service devforge.projects.v1
produces task-service devforge.tasks.v1
produces git-service devforge.repositories.v1

consumes notification-service devforge.identity.v1 devforge.security.v1 devforge.tasks.v1 devforge.repositories.v1
consumes analytics-service devforge.tasks.v1 devforge.repositories.v1 devforge.projects.v1

# review-service, documentation-service and chat-service have an identity and no grants: they
# publish nothing yet. Giving one a grant is a deliberate change to this file, not a default.

echo "kafka-setup: topics and ACLs in place"
kafka-acls --bootstrap-server "$BOOTSTRAP" --command-config "$CONFIG" --list
