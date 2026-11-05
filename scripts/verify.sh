#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# Tool installation and Docker startup are deliberately external to this check.
java -version
mvn -version
container=""
cleanup() { if [[ -n "$container" ]]; then docker rm -f "$container" >/dev/null; fi; }
trap cleanup EXIT
container=$(docker run --rm -d --memory=256m --tmpfs /var/lib/postgresql \
    -e POSTGRES_HOST_AUTH_METHOD=trust -e POSTGRES_USER=ormtest -e POSTGRES_DB=ormtest \
    -p 127.0.0.1::5432 postgres:18.6 -c timezone=UTC)
ready=false
for attempt in {1..60}; do
    if docker exec "$container" pg_isready -U ormtest -d ormtest >/dev/null 2>&1; then ready=true; break; fi
    sleep 1
done
[[ "$ready" == true ]]
port=$(docker port "$container" 5432/tcp | awk -F: '{print $NF}')
export ORM_TEST_URL="jdbc:postgresql://127.0.0.1:$port/ormtest"
export ORM_TEST_USER=ormtest
mvn -B -ntp clean "-Dsource.revision=$(git rev-parse HEAD)"
mkdir -p target/test-fixture
curl --fail --silent --show-error --location \
    https://raw.githubusercontent.com/finance-lab-project/budget/e19753ce63fd0a03f4b877ce41b82c483ca03860/src/main/resources/db/migration/V1__budget_schema.sql \
    -o target/test-fixture/V1__budget_schema.sql
printf '%s  %s\n' 33396e787b738b46ce9933d1ff17abacec8326430f49f96758cea466658f0f0c target/test-fixture/V1__budget_schema.sql | shasum -a 256 -c -
mvn -B -ntp verify "-Dsource.revision=$(git rev-parse HEAD)"
