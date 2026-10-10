#!/bin/sh
set -eu
umask 077
cd "$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
exec ./gradlew :test:twoNodeCoreLoop --console=plain --max-workers=2
