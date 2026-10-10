# Test-only TLS material

For `KafkaTlsIntegrationTest` only: a throwaway CA (`ca.pem`, its public certificate) and a broker
keystore for `localhost` signed by it (`broker.p12`, password `test-only-store-password`). The
CA's private key was discarded after signing. Nothing outside this test trusts this CA.

Real stacks generate their own: `infrastructure/docker/kafka/certs.sh` on first start.
