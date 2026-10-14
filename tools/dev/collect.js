// Collects public crash reports pasted in GitHub issues into a local folder (text only, never run).
// node collect.js <out dir>
const fs = require("fs"), path = require("path");
const out = process.argv[2];
fs.mkdirSync(out, { recursive: true });
const queries = [
  '"Minecraft Crash Report" "NeoForge" "1.21.1" in:body created:>2025-01-01',
  '"Minecraft Crash Report" "Forge" "1.20.1" "fml" in:body created:>2024-06-01',
  '"Minecraft Crash Report" "Mod loading" in:body created:>2025-01-01',
];
const sleep = ms => new Promise(r => setTimeout(r, ms));
let saved = 0;
(async () => {
  for (const q of queries) {
    for (let page = 1; page <= 2; page++) {
      const url = "https://api.github.com/search/issues?per_page=100&page=" + page + "&q=" + encodeURIComponent(q);
      const r = await fetch(url, { headers: { Accept: "application/vnd.github+json", "User-Agent": "check-engine-crash-library" } });
      const j = await r.json();
      if (!j.items) { console.log("stop:", j.message); break; }
      for (const it of j.items) {
        const body = it.body || "";
        const start = body.indexOf("---- Minecraft Crash Report ----");
        if (start < 0 || !/Description:/.test(body)) continue;
        let text = body.slice(start);
        const fence = text.indexOf("```");
        if (fence > 0) text = text.slice(0, fence);
        if (text.length < 400) continue;
        const name = it.html_url.replace("https://github.com/", "").replace(/[\/#]/g, "_") + ".txt";
        const file = path.join(out, name);
        if (fs.existsSync(file)) continue;
        fs.writeFileSync(file, "Source: " + it.html_url + "\n" + text);
        saved++;
      }
      console.log(q.slice(0, 40), "page", page, "saved so far", saved);
      await sleep(7000);
    }
  }
  console.log("done", saved);
})();
