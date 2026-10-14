#!/bin/bash
# ingame.sh <gradle project> <suspect jar> <out png>: fakes a finished Strip Mine, opens the dev client, screenshots.
proj=$1; jar=$2; out=$3
sp="C:/Users/Gaming/Desktop/MC/Projects/CheckEngine/tools/dev"
cd "C:/Users/Gaming/Desktop/MC/Projects/CheckEngine"
c=$proj/run/checkengine
mkdir -p $c/old-strip
cp $c/settings.properties $c/settings.properties.bak 2>/dev/null || echo "launch_popup=true" > $c/settings.properties.bak
echo "launch_popup=false" > $c/settings.properties
printf 'status=FOUND\nround=4\nsuspects=%s\ncrash=cecrash failed to load\nstartup=true\nround_start=0\nstart_count=16\n' "$jar" > $c/strip-mine.properties
export JAVA_HOME="C:/Users/Gaming/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2"
log="$TEMP/ig-$proj.log"
./gradlew ":$proj:runClient" --console=plain -Dorg.gradle.jvmargs="-Xmx3G -Djavax.net.ssl.trustStoreType=Windows-ROOT" > "$log" 2>&1 &
for i in $(seq 1 200); do grep -qE "Sound engine started|BUILD FAILED" "$log" && break; sleep 2; done
grep -E "Sound engine started|BUILD FAILED" "$log" | head -1
sleep 10
powershell -NoProfile -ExecutionPolicy Bypass -File "$sp/shot.ps1" -Out "$out"
powershell -NoProfile -Command 'Get-Process | Where-Object { $_.MainWindowTitle -like "Minecraft*" } | ForEach-Object { Stop-Process -Id $_.Id -Force }'
mv -f $c/settings.properties.bak $c/settings.properties
[ -f $c/strip-mine.properties ] && mv -f $c/strip-mine.properties $c/old-strip/strip-mine-fake-$(date +%s).properties
grep -iE "Exception|ERROR.*checkengine" "$log" | grep -iv "pkix\|ssl\|realms\|telemetry" | head -5
