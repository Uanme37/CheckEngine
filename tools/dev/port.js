const fs = require("fs");
const root = "C:/Users/Gaming/Desktop/MC/Projects/CheckEngine/";
const src = root + "mod/src/main/java/com/guiltypotato/checkengine/client/";
const read = f => fs.readFileSync(src + f, "utf8").split("\r\n").join("\n");
function must(s, a) { if (!s.includes(a)) { console.error("MISSING " + a.slice(0, 80)); process.exit(1); } }
function rep(s, a, b) { must(s, a); return s.split(a).join(b); }

// ---- 26.1 ----
{
  const out = root + "mod-26/src/main/java/com/guiltypotato/checkengine/client/";
  let d = read("DashScreen.java");
  d = rep(d, "import net.minecraft.Util;", "import net.minecraft.util.Util;");
  d = rep(d, "import net.minecraft.client.gui.GuiGraphics;", "import net.minecraft.client.gui.GuiGraphicsExtractor;\nimport net.minecraft.client.renderer.RenderPipelines;");
  d = rep(d, "import net.minecraft.resources.ResourceLocation;", "import net.minecraft.resources.Identifier;");
  d = rep(d, "ResourceLocation", "Identifier");
  d = rep(d, "GuiGraphics g", "GuiGraphicsExtractor g");
  d = rep(d, "public void renderBackground(", "public void extractBackground(");
  d = rep(d, "super.renderBackground(", "super.extractBackground(");
  d = rep(d, "g.drawString(", "g.text(");
  // 26.1 pipelines blend by themselves
  d = d.split(/^.*RenderSystem\.(?:enable|disable)Blend\(\);.*\n/gm).join("");
  d = rep(d, "g.blit(LOGO, lx - 6, ly - 6, 44, 44, cell * 128, 0, 128, 128, 1280, 128);", "g.blit(RenderPipelines.GUI_TEXTURED, LOGO, lx - 6, ly - 6, cell * 128, 0, 44, 44, 128, 128, 1280, 128);");
  d = rep(d, "g.blit(UAN, x + 10, fy + 3, 9, 9, 0, 0, 32, 32, 32, 32);", "g.blit(RenderPipelines.GUI_TEXTURED, UAN, x + 10, fy + 3, 0, 0, 9, 9, 32, 32, 32, 32);");
  d = rep(d, "g.pose().pushPose();", "g.pose().pushMatrix();");
  d = rep(d, "g.pose().translate(x, y, 0);", "g.pose().translate(x, y);");
  d = rep(d, "g.pose().scale(0.75f, 0.75f, 1f);", "g.pose().scale(0.75f, 0.75f);");
  d = rep(d, "g.pose().popPose();", "g.pose().popMatrix();");
  fs.writeFileSync(out + "DashScreen.java", d);

  let c = read("CrashDoctorScreen.java");
  c = rep(c, "import net.minecraft.Util;", "import net.minecraft.util.Util;");
  // 26.1 can open the title screen more than once; the last of our screens to close marks the check done.
  c = rep(c, "buttons(next, crash, self), () -> Minecraft.getInstance().setScreen(next));", "buttons(next, crash, self), () -> leave(next));");
  c = rep(c, "new Btn(tr(\"checkengine.screen.continue\"), () -> Minecraft.getInstance().setScreen(next), true),", "new Btn(tr(\"checkengine.screen.continue\"), () -> leave(next), true),");
  // every way out of Crash Doctor goes through leave() on 26.1
  c = c.split("() -> Minecraft.getInstance().setScreen(next)").join("() -> leave(next)");
  c = rep(c, "    private static String tr(String key) {", "    /** 26.1 can open the title screen more than once; the last of our screens to close marks the check done. */\n    private static void leave(Screen next) {\n        if (!(next instanceof BootWarningScreen)) ClientEvents.done = true;\n        Minecraft.getInstance().setScreen(next);\n    }\n\n    private static String tr(String key) {");
  fs.writeFileSync(out + "CrashDoctorScreen.java", c);

  let m = read("StripMineScreens.java");
  m = rep(m, "import net.minecraft.Util;", "import net.minecraft.util.Util;");
  fs.writeFileSync(out + "StripMineScreens.java", m);
  fs.writeFileSync(out + "PackUpdatedScreen.java", read("PackUpdatedScreen.java"));
  fs.writeFileSync(out + "SafeRemoveScreen.java", read("SafeRemoveScreen.java"));
  let w = read("BootWarningScreen.java");
  w = rep(w, "import net.minecraft.Util;", "import net.minecraft.util.Util;");
  // 26.1 can open the title screen more than once: leaving the heads-up marks the check done
  w = rep(w, "    private static void leave(Screen next) {\n", "    private static void leave(Screen next) {\n        ClientEvents.done = true;\n");
  fs.writeFileSync(out + "BootWarningScreen.java", w);
}

// ---- Forge 1.20.1 ----
{
  const out = root + "forge-mod/src/main/java/com/guiltypotato/checkengine/forge/client/";
  const forge = s => {
    s = rep(s, "package com.guiltypotato.checkengine.client;", "package com.guiltypotato.checkengine.forge.client;");
    s = s.split("import com.guiltypotato.checkengine.client.").join("import com.guiltypotato.checkengine.forge.client.");
    s = s.split("import static com.guiltypotato.checkengine.client.").join("import static com.guiltypotato.checkengine.forge.client.");
    s = s.split("import com.guiltypotato.checkengine.BootCheck;").join("import com.guiltypotato.checkengine.forge.BootCheck;");
    s = s.split("import com.guiltypotato.checkengine.CheckEngine;").join("import com.guiltypotato.checkengine.forge.CheckEngine;");
    s = s.split("import net.neoforged.fml.loading.FMLPaths;").join("import net.minecraftforge.fml.loading.FMLPaths;");
    return s;
  };
  let d = forge(read("DashScreen.java"));
  d = rep(d, "ResourceLocation.fromNamespaceAndPath(", "new ResourceLocation(");
  // 1.20.1 screens don't draw their own background: draw it, then the card, then the buttons.
  d = rep(d, `    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);`, `    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        card(g);
        super.render(g, mouseX, mouseY, partialTick);
    }

    private void card(GuiGraphics g) {`);
  // 1.20.1 has one scroll value, no sideways scrolling.
  d = rep(d, "public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {", "public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {");
  d = rep(d, "return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);", "return super.mouseScrolled(mouseX, mouseY, scrollY);");
  fs.writeFileSync(out + "DashScreen.java", d);
  fs.writeFileSync(out + "CrashDoctorScreen.java", forge(read("CrashDoctorScreen.java")));
  fs.writeFileSync(out + "StripMineScreens.java", forge(read("StripMineScreens.java")));
  fs.writeFileSync(out + "BootWarningScreen.java", forge(read("BootWarningScreen.java")));
  fs.writeFileSync(out + "SafeRemoveScreen.java", forge(read("SafeRemoveScreen.java")).split("import net.neoforged.fml.loading.FMLPaths;").join("import net.minecraftforge.fml.loading.FMLPaths;"));
  fs.writeFileSync(out + "PackUpdatedScreen.java", forge(read("PackUpdatedScreen.java")).split("import net.neoforged.fml.loading.FMLPaths;").join("import net.minecraftforge.fml.loading.FMLPaths;"));
}
console.log("ok");
