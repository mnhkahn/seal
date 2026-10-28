#!/usr/bin/env bash
set -euo pipefail
if [ -z "${PGYER_API_KEY:-}" ]; then
  echo "::error::PGYER_API_KEY secret is not set"
  exit 1
fi

APK_PATH="${1:?Usage: publish-pgyer.sh APK_PATH RELEASE_NOTES}"
NOTES_PATH="${2:?Release notes file is required}"
test -s "$APK_PATH"
test -s "$NOTES_PATH"
UPDATE_DESCRIPTION=$(<"$NOTES_PATH")
TOKEN_RESPONSE=$(curl --fail-with-body --silent --show-error --connect-timeout 15 --max-time 300 \
  --form-string "_api_key=${PGYER_API_KEY}" \
  --form "buildType=android" \
  --form "buildInstallType=1" \
  --form "buildInstallDate=2" \
  --form-string "buildUpdateDescription=${UPDATE_DESCRIPTION}" \
  https://www.pgyer.com/apiv2/app/getCOSToken)
if [ "$(jq -r '.code' <<<"$TOKEN_RESPONSE")" != "0" ]; then
  echo "::error::Pgyer did not issue an upload token: $(jq -r '.message // "unknown error"' <<<"$TOKEN_RESPONSE")"
  exit 1
fi

UPLOAD_ENDPOINT=$(jq -er '.data.endpoint | select(type == "string" and length > 0)' <<<"$TOKEN_RESPONSE")
BUILD_KEY=$(jq -er '.data.key | select(type == "string" and length > 0)' <<<"$TOKEN_RESPONSE")
UPLOAD_KEY=$(jq -er '.data.params.key | select(type == "string" and length > 0)' <<<"$TOKEN_RESPONSE")
SIGNATURE=$(jq -er '.data.params.signature | select(type == "string" and length > 0)' <<<"$TOKEN_RESPONSE")
SECURITY_TOKEN=$(jq -er '.data.params["x-cos-security-token"] | select(type == "string" and length > 0)' <<<"$TOKEN_RESPONSE")
curl --fail-with-body --silent --show-error --connect-timeout 15 --max-time 300 \
  --form-string "key=${UPLOAD_KEY}" \
  --form-string "signature=${SIGNATURE}" \
  --form-string "x-cos-security-token=${SECURITY_TOKEN}" \
  --form-string "x-cos-meta-file-name=Seal-release.apk" \
  --form "file=@${APK_PATH}" \
  "$UPLOAD_ENDPOINT"

# Pgyer documents 1246/1247 as processing states.  Other non-zero
# codes are terminal failures and must fail the release job.
for attempt in $(seq 1 60); do
  BUILD_RESPONSE=$(curl --fail-with-body --silent --show-error --connect-timeout 15 --max-time 300 --get \
    --data-urlencode "_api_key=${PGYER_API_KEY}" \
    --data-urlencode "buildKey=${BUILD_KEY}" \
    https://www.pgyer.com/apiv2/app/buildInfo)
  BUILD_CODE=$(jq -r '.code' <<<"$BUILD_RESPONSE")
  if [ "$BUILD_CODE" = "0" ]; then
    SHORT_URL=$(jq -er '.data.buildShortcutUrl | select(type == "string" and length > 0)' <<<"$BUILD_RESPONSE")
    PGYER_RELEASE_URL="https://www.pgyer.com/${SHORT_URL}"
    if [ -n "${GITHUB_ENV:-}" ]; then
      echo "PGYER_RELEASE_URL=${PGYER_RELEASE_URL}" >> "$GITHUB_ENV"
    fi
    if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
      echo "蒲公英下载页: ${PGYER_RELEASE_URL}" >> "$GITHUB_STEP_SUMMARY"
    fi
    echo "Pgyer release: ${PGYER_RELEASE_URL}"
    exit 0
  fi
  if [ "$BUILD_CODE" != "1246" ] && [ "$BUILD_CODE" != "1247" ]; then
    echo "::error::Pgyer publish failed (${BUILD_CODE}): $(jq -r '.message // "unknown error"' <<<"$BUILD_RESPONSE")"
    exit 1
  fi
  echo "Pgyer is publishing (attempt ${attempt}/60)"
  sleep 3
done

echo "::error::Timed out waiting for Pgyer to publish the APK"
exit 1
