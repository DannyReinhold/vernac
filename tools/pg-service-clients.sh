#!/bin/sh
# Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
# CI only: match pg_dump and psql to the PostgreSQL service image, including backpatches.
set -eu
: "${VERNAC_PG_CONTAINER:?PostgreSQL service container is required}"
: "${RUNNER_TEMP:?GitHub runner directory is required}"
: "${GITHUB_PATH:?GitHub path file is required}"
client_dir="$RUNNER_TEMP/vernac-postgres-clients"
mkdir -p "$client_dir"
for client in psql pg_dump; do
    printf '#!/bin/sh\nexec docker exec -i -e PGPASSWORD "$VERNAC_PG_CONTAINER" %s "$@"\n' "$client" > "$client_dir/$client"
    chmod +x "$client_dir/$client"
done
printf '%s\n' "$client_dir" >> "$GITHUB_PATH"
