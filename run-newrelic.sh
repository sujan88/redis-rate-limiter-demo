#!/bin/sh
# Read only the documented settings; never execute the properties file as shell code.
set -eu
cd "$(dirname "$0")"
if [ ! -f newrelic-local.properties ]; then
  echo "Copy newrelic-local.properties.example to newrelic-local.properties and fill in your settings." >&2
  exit 1
fi
while IFS='=' read -r name value || [ -n "$name" ]; do
  case "$name" in
    NEW_RELIC_LICENSE_KEY|NEW_RELIC_APP_NAME|NEW_RELIC_APPLICATION_LOGGING_ENABLED|NEW_RELIC_APPLICATION_LOGGING_FORWARDING_ENABLED|NEW_RELIC_OTLP_METRICS_URL|NEW_RELIC_AGENT_JAR)
      export "$name=$value" ;;
  esac
done < newrelic-local.properties
if [ "${NEW_RELIC_LICENSE_KEY:-}" = "replace-with-your-ingest-license-key" ] || [ -z "${NEW_RELIC_LICENSE_KEY:-}" ]; then
  echo "Set NEW_RELIC_LICENSE_KEY in newrelic-local.properties." >&2
  exit 1
fi
if [ ! -f "${NEW_RELIC_AGENT_JAR:-}" ]; then
  echo "Set NEW_RELIC_AGENT_JAR to your installed newrelic.jar path in newrelic-local.properties." >&2
  exit 1
fi
exec java "-javaagent:$NEW_RELIC_AGENT_JAR" \
  -jar target/redis-rate-limiter-demo-1.0.0.jar \
  --spring.profiles.active=newrelic "$@"
