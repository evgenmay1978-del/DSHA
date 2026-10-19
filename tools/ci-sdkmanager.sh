#!/usr/bin/env bash
# Install Android SDK packages on a CI runner: tools/ci-sdkmanager.sh "platforms;android-37" ...
# Runner images do not reliably put sdkmanager on PATH (ubuntu-24.04: exit 127), so prefer the
# SDK's own cmdline-tools and fail with a readable annotation, since job logs are not always
# available to the agents that read CI results.
set -euo pipefail
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
manager="$sdk/cmdline-tools/latest/bin/sdkmanager"
[ -n "$sdk" ] && [ -x "$manager" ] || manager="$(command -v sdkmanager || true)"
if [ -z "$manager" ]; then
  echo "::error title=sdkmanager not found::ANDROID_HOME=$sdk; cmdline-tools: $(ls "$sdk/cmdline-tools" 2>&1 | tr '\n' ' ')"
  exit 1
fi
echo "::notice title=sdkmanager::$manager"
# Licenses are normally pre-accepted on hosted runners; accepting again is idempotent.
(yes 2>/dev/null || true) | "$manager" --licenses > /dev/null
"$manager" "$@"
