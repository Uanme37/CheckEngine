#!/bin/bash
# loop.sh <gradle project> <run dir> <cli jar> <bad jar or ""> [rounds]
# Plays Strip Mine on a dev server: "worked" = server got to Done (marker written like the title screen does, then
# stopped over RCON so the shutdown hook runs). The bad jar makes the server crash while loading.
proj=$1; run=$2; cli=$3; bad=$4; max=${5:-12}
here=$(dirname "$0")
cd "C:/Users/Gaming/Desktop/MC/Projects/CheckEngine"
export JAVA_HOME="C:/Users/Gaming/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2"
java26="C:/Users/Gaming/curseforge/minecraft/Install/runtime"
for i in $(seq 1 $max); do
  st=$(grep -m1 "^status=" "$run/checkengine/strip-mine.properties" | cut -d= -f2)
  rd=$(grep -m1 "^round=" "$run/checkengine/strip-mine.properties" | cut -d= -f2)
  if [ "$st" != "RUNNING" ] && [ "$st" != "PENDING" ]; then echo "STOP: status=$st"; break; fi
  on=$(ls "$run/mods" | grep -c '\.jar$'); off=$(ls "$run/mods" | grep -c '\.jar\.disabled$')
  badon=no; [ -n "$bad" ] && [ -f "$run/mods/$bad" ] && badon=yes
  log="$TEMP/loop-$proj-$i.log"
  ./gradlew ":$proj:runServer" --console=plain -Dorg.gradle.jvmargs="-Xmx3G -Djavax.net.ssl.trustStoreType=Windows-ROOT" > "$log" 2>&1 &
  gp=$!
  result=crashed
  for t in $(seq 1 240); do
    if grep -q "Done (" "$log"; then result=worked; break; fi
    if ! kill -0 $gp 2>/dev/null; then break; fi
    sleep 1
  done
  if [ $result = worked ]; then
    date +%s000 > "$run/checkengine/strip-mine-worked.marker"
    sleep 1
    node "$here/rcon.js" "$run/server.properties" stop > /dev/null
  fi
  wait $gp
  skip=$(grep -o "Strip Mine round, skipping [0-9]* mods" "$log" | head -1)
  echo "launch $i: round $rd, $on jars on / $off off, bad on: $badon -> $result ${skip:+($skip)}"
  tail -1 "$run/checkengine/strip-mine.log"
done
st=$(grep -m1 "^status=" "$run/checkengine/strip-mine.properties" | cut -d= -f2)
echo "final status=$st"
grep -A1 "^suspects=" "$run/checkengine/strip-mine.properties" | head -2
