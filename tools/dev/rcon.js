// node rcon.js <server.properties> <command>
const fs = require("fs"), net = require("net");
const props = Object.fromEntries(fs.readFileSync(process.argv[2], "utf8").split(/\r?\n/)
  .filter(l => l.includes("=")).map(l => [l.slice(0, l.indexOf("=")), l.slice(l.indexOf("=") + 1)]));
const pkt = (id, type, body) => { const b = Buffer.from(body, "utf8"); const out = Buffer.alloc(14 + b.length);
  out.writeInt32LE(10 + b.length, 0); out.writeInt32LE(id, 4); out.writeInt32LE(type, 8); b.copy(out, 12); return out; };
const s = net.connect(+props["rcon.port"], "127.0.0.1", () => s.write(pkt(1, 3, props["rcon.password"])));
let authed = false;
s.on("data", d => { if (!authed) { authed = true; s.write(pkt(2, 2, process.argv[3])); setTimeout(() => s.destroy(), 1500); }
  else { console.log(d.slice(12, d.length - 2).toString()); } });
s.on("error", e => { console.log("rcon error " + e.message); });
s.on("close", () => process.exit(0));
setTimeout(() => process.exit(0), 5000);
