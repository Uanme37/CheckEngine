package com.guiltypotato.checkengine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guiltypotato.checkengine.core.crash.JoinFailure;
import org.junit.jupiter.api.Test;

/** "Invalid player data" kicks, from the log (shapes taken from public GitHub and forum reports). */
class JoinFailureTest {
    private static final String HEAD = """
            [21Oct2026 12:00:01.100] [Server thread/INFO] [net.minecraft.server.MinecraftServer/]: Done (8.2s)!
            [21Oct2026 12:00:09.500] [Server thread/ERROR] [net.minecraft.server.players.PlayerList/]: Couldn't place player in world
            """;
    private static final String TAIL = """
            [21Oct2026 12:00:09.510] [Server thread/INFO] [net.minecraft.server.network.ServerGamePacketListenerImpl/]: Steve lost connection: Invalid player data
            """;

    @Test
    void oldLuckPermsIsNamedWithTheFixedVersion() {
        String log = HEAD + """
                java.lang.NullPointerException: Cannot invoke "net.luckperms.api.model.user.User.getCachedData()" because "user" is null
                \tat TRANSFORMER/luckperms@5.4.140/me.lucko.luckperms.neoforge.listeners.NeoForgePlatformListener.onPlayerLogin(NeoForgePlatformListener.java:58) ~[LuckPerms-NeoForge-5.4.140.jar%23201!/:?]
                \tat TRANSFORMER/minecraft@1.21.1/net.minecraft.server.players.PlayerList.placeNewPlayer(PlayerList.java:180) ~[neoforge-21.1.252.jar%23190!/:?]
                """ + TAIL;
        JoinFailure.Result r = JoinFailure.fromLog(log);
        assertEquals("Steve", r.player());
        assertEquals("LuckPerms-NeoForge-5.4.140.jar", r.mod());
        assertEquals("LuckPerms broke the join", r.finding().title());
        assertTrue(r.finding().detail().contains("5.4.140"), r.finding().detail());
        assertTrue(r.finding().fix().startsWith("Update LuckPerms to 5.4.150"), r.finding().fix());
    }

    @Test
    void luckPermsOnAnotherGameVersionDoesNotBlameThe1211Bug() {
        String log = HEAD + """
                java.lang.NullPointerException: Cannot invoke "net.luckperms.api.model.user.User.getCachedData()" because "user" is null
                \tat TRANSFORMER/luckperms@5.4.140/me.lucko.luckperms.neoforge.listeners.NeoForgePlatformListener.onPlayerLogin(NeoForgePlatformListener.java:58) ~[LuckPerms-NeoForge-5.4.140.jar%23201!/:?]
                \tat TRANSFORMER/minecraft@26.1.2/net.minecraft.server.players.PlayerList.placeNewPlayer(PlayerList.java:180) ~[neoforge-26.1.2.15.jar%23190!/:?]
                """ + TAIL;
        JoinFailure.Result r = JoinFailure.fromLog(log);
        assertFalse(r.finding().detail().contains("1.21.1"), r.finding().detail());
        assertTrue(r.finding().fix().startsWith("Update LuckPerms, and if it keeps happening"), r.finding().fix());
    }

    @Test
    void ftbEssentialsAndBrokenSaveFile() {
        String ftb = HEAD + """
                java.lang.IllegalStateException: Failed to read player data
                \tat TRANSFORMER/ftbessentials@2101.1.0/dev.ftb.mods.ftbessentials.util.FTBEPlayerData.load(FTBEPlayerData.java:90) ~[ftb-essentials-neoforge-2101.1.0.jar%23150!/:?]
                """ + TAIL;
        assertEquals("FTB Essentials broke the join", JoinFailure.fromLog(ftb).finding().title());

        String broken = HEAD + """
                java.io.EOFException: Unexpected end of ZLIB input stream
                \tat java.base/java.util.zip.InflaterInputStream.fill(InflaterInputStream.java:244)
                \tat TRANSFORMER/minecraft@1.21.1/net.minecraft.nbt.NbtIo.readCompressed(NbtIo.java:62) ~[neoforge-21.1.252.jar%23190!/:?]
                """ + TAIL;
        JoinFailure.Result r = JoinFailure.fromLog(broken);
        assertEquals("The player's save file is broken", r.finding().title());
        assertTrue(r.finding().fix().contains(".dat_old"), r.finding().fix());
        assertNull(r.mod(), "the game's own jar is never blamed");
    }

    @Test
    void otherModsByMixinOrJarAndNoKickAtAll() {
        String mixin = HEAD + """
                java.lang.ClassCastException: class A cannot be cast to class B
                \tat TRANSFORMER/minecraft@1.21.1/net.minecraft.server.players.PlayerList.handler$zbc000$coolmod$onPlace(PlayerList.java:900) ~[neoforge-21.1.252.jar%23190!/:?]
                """ + TAIL;
        JoinFailure.Result r = JoinFailure.fromLog(mixin);
        assertEquals("coolmod", r.mod());
        assertEquals("coolmod broke the join", r.finding().title());
        assertNull(JoinFailure.fromLog("[21Oct2026 12:00:01.100] [Server thread/INFO] [x/]: Done (8.2s)!\n"));
    }

    @Test
    void lastSessionKickIsShownOnceOnTheNextLaunch(@org.junit.jupiter.api.io.TempDir java.nio.file.Path game) throws Exception {
        java.nio.file.Path logs = java.nio.file.Files.createDirectories(game.resolve("logs"));
        String log = HEAD + "java.lang.IllegalStateException: boom\n\tat x.Y.z(Y.java:1) ~[coolmod-1.0.jar%2312!/:?]\n" + TAIL;
        try (var out = new java.util.zip.GZIPOutputStream(java.nio.file.Files.newOutputStream(logs.resolve("2026-10-21-1.log.gz")))) {
            out.write(log.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        java.nio.file.Path ce = game.resolve("checkengine");
        JoinFailure.Result r = JoinFailure.lastSession(game, ce);
        assertEquals("coolmod-1.0.jar", r.mod());
        JoinFailure.markShown(ce, r);
        assertNull(JoinFailure.lastSession(game, ce), "already explained");
    }
}
