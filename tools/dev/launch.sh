#!/bin/bash
# launch.sh <gradle project> [seconds]: starts its dev client and waits until the Check Engine window shows
# ("window"), the title screen is reached ("title"), a crash report appears ("crashed") or the game exits ("exited"). Prints that state.
proj=$1; max=${2:-240}
cd "C:/Users/Gaming/Desktop/MC/Projects/CheckEngine"
export JAVA_HOME="C:/Users/Gaming/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2"
log="$TEMP/launch-$proj.log"
latest="$proj/run/logs/latest.log"
start=$(date +%s)
./gradlew ":$proj:runClient" --console=plain -Dorg.gradle.jvmargs="-Xmx3G -Djavax.net.ssl.trustStoreType=Windows-ROOT" > "$log" 2>&1 &
gp=$!
for t in $(seq 1 "$max"); do
  sleep 1
  if powershell -NoProfile -ExecutionPolicy Bypass -File tools/dev/haswin.ps1; then
    echo window; exit 0
  fi
  if [ -f "$latest" ] && [ "$(stat -c %Y "$latest")" -ge "$start" ] && grep -q "title screen in" "$latest"; then
    sleep 4; echo title; exit 0
  fi
  if [ -n "$(find "$proj/run/crash-reports" -newermt "@$start" -name 'crash-*' 2>/dev/null)" ]; then
    sleep 3; echo crashed; exit 0
  fi
  if ! kill -0 $gp 2>/dev/null; then echo exited; exit 0; fi
done
echo timeout
