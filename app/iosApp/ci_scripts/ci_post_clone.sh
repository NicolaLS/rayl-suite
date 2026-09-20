#!/bin/zsh
set -euo pipefail

# Only do this on Xcode Cloud
if [ "${CI_XCODE_CLOUD:-}" = "TRUE" ]; then
  echo "== ci_post_clone.sh: installing OpenJDK 21 via Homebrew =="
  brew install openjdk@21
fi
