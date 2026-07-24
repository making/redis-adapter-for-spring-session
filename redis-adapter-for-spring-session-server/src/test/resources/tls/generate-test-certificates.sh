#!/bin/bash
# Regenerates the self-signed certificate material the TLS tests run on.
#
# Everything this writes is TEST material: the keys are unencrypted, they are committed to
# the repository, and they are trusted by nothing but the tests in this module. Never point
# a deployment at them.
#
# It builds a throwaway CA and signs three certificates with it:
#
#   ca.crt                 the CA the client trusts and the server verifies client
#                          certificates against
#   server.crt/server.key  what the adapter serves, valid for 127.0.0.1 and localhost so
#                          that a client verifying the host name is satisfied
#   server-rotated.crt     a renewal of the server certificate: same CA, same subject and
#   server-rotated.key     same subject alternative names, different key and serial, which
#                          is what a certificate rotated on disk looks like. The tests
#                          copy it over the server material and expect the new serial to
#                          reach the next client (redis-adapter TLS reload)
#   client.crt/client.key  what a client presents when the server asks for one
#                          (redis-adapter.ssl.client-auth)
#
# Keys are written as unencrypted PKCS#8, which is what both Spring Boot's PEM bundles and
# Lettuce read. The CA key is deliberately not kept: rerun this script to get a fresh set.
#
# Usage: ./generate-test-certificates.sh
set -euo pipefail

cd "$(dirname "$0")"

DAYS=3650
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

# The CA. Its key never leaves the temporary directory.
openssl req -x509 -newkey rsa:2048 -sha256 -nodes -days "${DAYS}" \
  -keyout "${WORK}/ca.key" -out ca.crt \
  -subj "/CN=Redis Adapter for Spring Session Test CA"

# The server certificate. The subject alternative names are what a client checks the host
# it dialled against, so both loopback spellings the tests use are listed.
openssl req -newkey rsa:2048 -sha256 -nodes \
  -keyout server.key -out "${WORK}/server.csr" \
  -subj "/CN=localhost"
openssl x509 -req -in "${WORK}/server.csr" -sha256 -days "${DAYS}" \
  -CA ca.crt -CAkey "${WORK}/ca.key" -CAserial "${WORK}/ca.srl" -CAcreateserial \
  -extfile <(printf 'subjectAltName=DNS:localhost,IP:127.0.0.1\nextendedKeyUsage=serverAuth\n') \
  -out server.crt

# The renewal of the server certificate, for the rotation tests. Everything a client
# verifies is unchanged; only the key and the serial differ, exactly as they do when
# cert-manager or Vault renews a certificate in place.
openssl req -newkey rsa:2048 -sha256 -nodes \
  -keyout server-rotated.key -out "${WORK}/server-rotated.csr" \
  -subj "/CN=localhost"
openssl x509 -req -in "${WORK}/server-rotated.csr" -sha256 -days "${DAYS}" \
  -CA ca.crt -CAkey "${WORK}/ca.key" -CAserial "${WORK}/ca.srl" -CAcreateserial \
  -extfile <(printf 'subjectAltName=DNS:localhost,IP:127.0.0.1\nextendedKeyUsage=serverAuth\n') \
  -out server-rotated.crt

# The client certificate, for the mutual-TLS tests.
openssl req -newkey rsa:2048 -sha256 -nodes \
  -keyout client.key -out "${WORK}/client.csr" \
  -subj "/CN=redis-adapter-test-client"
openssl x509 -req -in "${WORK}/client.csr" -sha256 -days "${DAYS}" \
  -CA ca.crt -CAkey "${WORK}/ca.key" -CAserial "${WORK}/ca.srl" -CAcreateserial \
  -extfile <(printf 'extendedKeyUsage=clientAuth\n') \
  -out client.crt

echo "Wrote ca.crt, server.crt, server.key, server-rotated.crt, server-rotated.key, client.crt and client.key to $(pwd)"
