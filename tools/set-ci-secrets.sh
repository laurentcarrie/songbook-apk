#!/usr/bin/env bash
# Stores the secrets the "apk to gdrive" workflow needs in the GitHub repo, from
# keystore.properties and the local rclone config. Run it once, from the repo root:
#   tools/set-ci-secrets.sh
set -euo pipefail
cd "$(dirname "$0")/.."
prop() { sed -n "s/^$1=//p" keystore.properties; }

store=$(prop storeFile)
[ -f "$store" ] || { echo "keystore $store not found"; exit 1; }

base64 -w0 "$store" | gh secret set KEYSTORE_B64
prop storePassword | tr -d '\n' | gh secret set STORE_PASSWORD
prop keyAlias      | tr -d '\n' | gh secret set KEY_ALIAS
prop keyPassword   | tr -d '\n' | gh secret set KEY_PASSWORD
# only the [gdrive] remote, not the other remotes of the local rclone.conf
rclone config show gdrive | sed '1s/^.*$/[gdrive]/' | base64 -w0 | gh secret set RCLONE_CONF_B64
echo "secrets set:"; gh secret list
