package com.guiltypotato.checkengine;

import com.guiltypotato.checkengine.core.data.DataScanner;
import com.guiltypotato.checkengine.core.scan.Finding;
import com.guiltypotato.checkengine.core.scan.Report;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import net.minecraft.util.Util;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;

/** /checkengine scan: recipes, loot tables and FTB Quests that use items from mods that aren't installed. */
final class DataScan {
    private DataScan() {}

    static int run(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        source.sendSuccess(() -> Component.literal("Check Engine: checking recipes, loot tables and quests..."), false);
        // Grab handles on the server thread, read them in the background so the game doesn't freeze.
        ResourceManager resources = server.getResourceManager();
        Map<Identifier, Resource> recipes = resources.listResources("recipe", DataScan::isJson);
        Map<Identifier, Resource> lootTables = resources.listResources("loot_table", DataScan::isJson);
        Set<String> namespaces = knownNamespaces();

        CompletableFuture.supplyAsync(() -> {
            DataScanner scanner = new DataScanner(namespaces::contains);
            recipes.forEach((path, r) -> scanner.recipe(id(path, "recipe/"), r.sourcePackId(), read(r)));
            lootTables.forEach((path, r) -> scanner.lootTable(id(path, "loot_table/"), r.sourcePackId(), read(r)));
            Path quests = FMLPaths.CONFIGDIR.get().resolve("ftbquests").resolve("quests");
            if (Files.isDirectory(quests)) {
                try (Stream<Path> files = Files.walk(quests)) {
                    for (Path p : files.filter(f -> f.toString().endsWith(".snbt")).toList()) {
                        scanner.questFile(quests.relativize(p).toString().replace('\\', '/'),
                                Files.readString(p, StandardCharsets.UTF_8));
                    }
                } catch (IOException e) {
                    throw new RuntimeException("couldn't read FTB Quests files: " + e.getMessage(), e);
                }
            }
            return save(scanner);
        }, Util.backgroundExecutor()).whenComplete((done, error) -> server.execute(() -> {
            if (error != null) {
                CheckEngine.LOGGER.error("Check Engine: data scan failed", error);
                source.sendFailure(Component.literal("Check Engine: scan failed: " + error.getMessage()));
            } else {
                source.sendSuccess(() -> done, true);
            }
        }));
        return 1;
    }

    private static Component save(DataScanner scanner) {
        List<Finding> findings = scanner.findings();
        StringBuilder sb = new StringBuilder("Check Engine data scan\n");
        sb.append("Checked: ").append(scanner.checked(DataScanner.Kind.RECIPE)).append(" recipes, ")
                .append(scanner.checked(DataScanner.Kind.LOOT_TABLE)).append(" loot tables, ")
                .append(scanner.checked(DataScanner.Kind.QUEST)).append(" FTB Quests files\n\n");
        Report.appendFindings(sb, findings);
        Path file = BootCheck.outputFolder().resolve("scan-report.txt");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("couldn't save the report: " + e.getMessage(), e);
        }
        String path = file.toAbsolutePath().toString();
        Component link = Component.literal("checkengine/scan-report.txt").withStyle(s -> s.withUnderlined(true)
                .withClickEvent(new ClickEvent.OpenFile(path))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(path))));
        return Component.literal("Check Engine: " + Report.summary(findings) + ". Report: ").append(link);
    }

    /** "minecraft", every loaded mod id, and any namespace that registered items, blocks, fluids or recipes. */
    private static Set<String> knownNamespaces() {
        Set<String> out = new HashSet<>(Set.of("minecraft", "c", "neoforge"));
        ModList.get().getMods().forEach(m -> out.add(m.getModId()));
        for (Registry<?> r : List.<Registry<?>>of(BuiltInRegistries.ITEM, BuiltInRegistries.BLOCK,
                BuiltInRegistries.FLUID, BuiltInRegistries.RECIPE_SERIALIZER, BuiltInRegistries.RECIPE_TYPE)) {
            r.keySet().forEach(k -> out.add(k.getNamespace()));
        }
        return out;
    }

    private static boolean isJson(Identifier path) {
        return path.getPath().endsWith(".json");
    }

    /** data/create/recipe/crushing/ore.json -> create:crushing/ore */
    private static String id(Identifier path, String folder) {
        String p = path.getPath();
        return path.getNamespace() + ":" + p.substring(folder.length(), p.length() - ".json".length());
    }

    private static String read(Resource r) {
        try (Reader reader = r.openAsReader()) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[8192];
            for (int n; (n = reader.read(buf)) > 0; ) sb.append(buf, 0, n);
            return sb.toString();
        } catch (IOException e) {
            return ""; // counted as unreadable by the scanner
        }
    }
}
