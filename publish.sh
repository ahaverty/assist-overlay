#!/usr/bin/env bash
# Build a signed release and publish it for the app's self-update
# (served by claude-launcher at https://3d.ahaverty.com/claude/assist-app/, LAN + WireGuard only).
# Usage: ./publish.sh "What changed, one line"
set -euo pipefail
cd "$(dirname "$0")"
export JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"
./gradlew assembleRelease --console=plain -q 2>&1 | grep -v "^w: " || true
apk=app/build/outputs/apk/release/app-release.apk
code=$(python -c "import re;print(re.search(r'versionCode = (\d+)', open('app/build.gradle.kts').read())[1])")
name=$(python -c "import re;print(re.search(r'versionName = \"([^\"]+)', open('app/build.gradle.kts').read())[1])")
mkdir -p dist
cp "$apk" dist/assist.apk
cp "$apk" "dist/assist-$name.apk"
python -c "import json,sys; json.dump({'versionCode': int(sys.argv[1]), 'versionName': sys.argv[2], 'notes': sys.argv[3]}, open('dist/version.json','w'))" "$code" "$name" "${1:-}"
echo "published $name ($code)"
