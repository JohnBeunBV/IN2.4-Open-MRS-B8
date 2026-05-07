#!/bin/sh
# vault-init.sh — Seeds provider credentials into HashiCorp Vault
# Runs once after Vault starts. In production, replace these with real values.

echo "Waiting for Vault to be ready..."
sleep 5

vault secrets enable -path=secret kv-v2 2>/dev/null || true

echo "Seeding SwiftSend credentials..."
vault kv put secret/communicatiemodule/providers/swiftsend \
  api_key="REPLACE_WITH_REAL_SWIFTSEND_KEY"

echo "Seeding SecurePost credentials..."
vault kv put secret/communicatiemodule/providers/securepost \
  username="REPLACE_WITH_REAL_SECUREPOST_USER" \
  password="REPLACE_WITH_REAL_SECUREPOST_PASS"

echo "Seeding LegacyLink credentials..."
vault kv put secret/communicatiemodule/providers/legacylink \
  username="REPLACE_WITH_REAL_LEGACYLINK_USER" \
  password="REPLACE_WITH_REAL_LEGACYLINK_PASS"

echo "Seeding AsyncFlow credentials..."
vault kv put secret/communicatiemodule/providers/asyncflow \
  api_key="REPLACE_WITH_REAL_ASYNCFLOW_KEY"

echo "Vault init complete."
