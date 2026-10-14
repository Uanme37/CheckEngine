package com.guiltypotato.checkengine.core.crash;

import com.guiltypotato.checkengine.core.scan.Finding;
import com.guiltypotato.checkengine.core.scan.Finding.Severity;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Silent crashes: the game just closes ("exit code -1073741819") with no crash report, because it died outside Java
 * (a graphics driver, the window layer, a mod's native code...). Java leaves an hs_err_pid*.log in the game folder
 * instead, and its "Problematic frame" names the file that crashed. This turns that into a plain explanation.
 */
public final class NativeCrash {
    /** The first line of every hs_err_pid file. */
    public static final String MARKER = "A fatal error has been detected by the Java Runtime Environment";
    public static final String CODE = "native-crash";

    private static final Pattern FRAME = Pattern.compile("#\\s+([CJjVv])\\s+(?:\\[([^\\]+]+)[^\\]]*\\]|\\d+ \\S+ (\\S+)|(\\S+))");
    /** "#  EXCEPTION_ACCESS_VIOLATION (0xc0000005) at pc=..." / "#  SIGSEGV (0xb) at pc=..." */
    private static final Pattern SIGNAL = Pattern.compile("#\\s+(EXCEPTION_\\w+|SIG[A-Z]+)");
    /** A Java method in the Java frames: "J 77366 c1 top.leonx.irisflw.vertex.X.getNX(I)F" or "j  a.b.C.m()V+5". */
    private static final Pattern JAVA_FRAME = Pattern.compile("^[Jj]\\s+(?:\\d+\\s+\\S+\\s+)?([a-zA-Z_$][\\w$]*(?:\\.[\\w$]+)+)\\.[\\w$<>]+\\(");

    private NativeCrash() {}

    public static boolean isNativeCrash(String text) {
        return text.contains(MARKER);
    }

    public static CrashTranslator.Explanation explain(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        String frame = problematicFile(text);
        String lib = frame == null ? "" : frame.toLowerCase(Locale.ROOT);
        Matcher sig = SIGNAL.matcher(text);
        String signal = sig.find() ? sig.group(1) : "a native crash";
        String modCode = modFromJavaFrames(text);
        String where = frame == null ? "" : " (it crashed in " + frame + ")";
        String noReport = "The game closed without a normal crash report, because it died outside Java" + where + ".";
        List<Finding> out = new ArrayList<>();

        if (lower.contains("insufficient memory for the java runtime") || lower.contains("native memory allocation")
                || lower.contains("out of memory error")) {
            out.add(new Finding(Severity.ERROR, CODE, "The PC ran out of memory",
                    "Windows couldn't give the game the memory it asked for, so it closed. The game's memory setting "
                            + "is higher than this PC can spare right now.",
                    "Lower the game's memory a little (leave at least 3-4 GB for Windows), close other programs, and "
                            + "make sure Windows' page file is on (Automatic).", List.of()));
        } else if (gpu(lib) != null) {
            String vendor = gpu(lib);
            out.add(new Finding(Severity.ERROR, CODE, "Graphics driver crash (" + vendor + ")",
                    noReport + " That file is the " + vendor + " graphics driver." + shaders(lower),
                    driverFix(vendor), List.of()));
        } else if (lib.contains("openglon12")) {
            out.add(new Finding(Severity.ERROR, CODE, "Crash in Windows' OpenGL compatibility layer",
                    noReport + " That's Microsoft's \"OpenCL and OpenGL Compatibility Pack\", which stands in for a "
                            + "real graphics driver.",
                    "Install the real driver for your graphics card (AMD, NVIDIA, Intel or Qualcomm), and remove the "
                            + "OpenCL and OpenGL Compatibility Pack from Windows' apps.", List.of()));
        } else if (lib.startsWith("lwjgl_stb")) {
            out.add(new Finding(Severity.ERROR, CODE, "Crash while loading an image",
                    noReport + " " + frame + " reads images (textures, fonts, the pack icon), so one of them is "
                            + "probably broken or far too big.",
                    "Try without your resource packs first. If it still happens, a mod's textures are the cause: "
                            + CrashTranslator.STRIP_MINE_HINT, List.of()));
        } else if (lib.startsWith("lwjgl") || lib.startsWith("liblwjgl") || lib.contains("glfw") || lib.startsWith("libx11") || lib.contains("libegl")
                || lib.contains("libgldispatch")) {
            out.add(new Finding(Severity.ERROR, CODE, "Crash in the game's window and graphics layer",
                    noReport + " " + frame + " is the part of the game that talks to the graphics driver, so the "
                            + "driver is the usual cause." + shaders(lower),
                    "Update your graphics driver. If it still happens, try without shaders and any overlay programs "
                            + "(Discord, MSI Afterburner, OBS).", List.of()));
        } else if (overlay(lib) != null) {
            out.add(new Finding(Severity.ERROR, CODE, "An overlay program crashed the game (" + overlay(lib) + ")",
                    noReport + " " + frame + " belongs to " + overlay(lib) + ", which draws on top of games.",
                    "Turn off " + overlay(lib) + "'s in-game overlay for Minecraft, or close it while playing.",
                    List.of()));
        } else if (lib.contains("asyncprofiler")) {
            out.add(new Finding(Severity.ERROR, CODE, "spark's profiler crashed the game",
                    noReport + " spark (the performance profiler mod) uses a native helper that some PCs don't like.",
                    "In config/spark/config.json set \"backgroundProfiler\" to false, or remove spark.", List.of()));
        } else if (lib.contains("openal")) {
            out.add(new Finding(Severity.ERROR, CODE, "Sound system crash",
                    noReport + " That file plays the game's sound.",
                    "Update your sound driver, unplug and replug the headset, or change the default audio device. "
                            + "Sound mods (Sound Physics, voice chat) can cause it too.", List.of()));
        } else if (lib.contains("libcef")) {
            out.add(new Finding(Severity.ERROR, CODE, "A web browser mod crashed the game",
                    noReport + " " + frame + " is a built-in web browser (used by mods like MCEF or WebDisplays).",
                    "Update or remove the mod that shows web pages in game.", List.of()));
        } else if (lib.contains("jvm") || lib.startsWith("msvcr") || lib.startsWith("vcruntime")
                || lib.startsWith("libzip") || lib.startsWith("zip.dll") || lib.startsWith("awt.dll")
                || lib.startsWith("jdwp") || lib.startsWith("msvcp")) {
            String mod = modCode == null ? "" : " The last code running was " + modCode + ", so that mod may be the cause.";
            out.add(new Finding(Severity.ERROR, CODE, "Java itself crashed",
                    noReport + " " + frame + " is Java itself." + mod,
                    (modCode != null ? "Update or remove the mod that " + modCode + " belongs to. " : "")
                            + "Otherwise: lower the game's memory a little, turn off any CPU or RAM overclock, and use "
                            + "the Java the launcher picks.", List.of()));
        } else if (!lib.isEmpty() && !lib.matches("ntdll.*|kernel.*|ucrtbase.*|libc[.-].*|libc|libpthread.*"
                + "|libsystem_.*|libdl.*|libm[.-].*|appkit|corefoundation|libobjc.*|libdispatch.*|ld-linux.*|libgtk.*")) {
            out.add(new Finding(Severity.ERROR, CODE, "A mod's native code crashed the game (" + frame + ")",
                    noReport + " " + frame + " isn't part of Minecraft or Java, so it's most likely a mod's own "
                            + "native code (physics, video, sound or performance mods ship these)."
                            + (modCode == null ? "" : " The last Java code running was " + modCode + "."),
                    "Update or remove the mod that comes with " + frame + ". "
                            + CrashTranslator.STRIP_MINE_HINT, List.of()));
        } else {
            out.add(new Finding(Severity.ERROR, CODE, "The game crashed outside Java (" + signal + ")",
                    noReport + (modCode == null ? "" : " The last Java code running was " + modCode + "."),
                    "Update your graphics driver first: it's the most common cause. " + CrashTranslator.STRIP_MINE_HINT,
                    List.of()));
        }
        return new CrashTranslator.Explanation("The game closed: " + signal, frame, out);
    }

    /** The file named in "# Problematic frame:", e.g. "atio6axx.dll", or null. */
    static String problematicFile(String text) {
        int i = text.indexOf("Problematic frame:");
        if (i < 0) return null;
        String rest = text.substring(i + "Problematic frame:".length()).strip();
        String line = rest.lines().findFirst().orElse("");
        Matcher m = FRAME.matcher(line);
        if (m.find() && m.group(2) != null) return m.group(2).strip();
        // It jumped to an empty address ("C  0x0000000000000000") or was in Java: the first native file further down
        // the stack is the one that called it.
        int stack = text.indexOf("Native frames:");
        if (stack < 0) return null;
        Matcher c = Pattern.compile("(?m)^\\s*#?\\s*C\\s+\\[([^\\]+]+)").matcher(text.substring(stack));
        while (c.find()) {
            String lib = c.group(1).strip();
            if (!lib.toLowerCase(Locale.ROOT).matches("ntdll.*|kernel.*|ucrtbase.*|libc[.-].*|libpthread.*")) return lib;
        }
        return null;
    }

    /** The first Java frame that isn't Java, Minecraft, the loader or LWJGL: probably the mod that was running. */
    static String modFromJavaFrames(String text) {
        for (String raw : text.split("\n")) {
            Matcher m = JAVA_FRAME.matcher(raw.replaceFirst("^#\\s*", "").strip());
            if (!m.find()) continue;
            String cls = m.group(1);
            if (cls.matches("(java|javax|jdk|sun|com\\.sun|org\\.lwjgl|net\\.minecraft|com\\.mojang|net\\.neoforged|"
                    + "net\\.minecraftforge|cpw|org\\.spongepowered|it\\.unimi|com\\.google|io\\.netty)\\..*")) continue;
            String[] parts = cls.split("\\.");
            return String.join(".", java.util.Arrays.copyOf(parts, Math.min(3, parts.length - 1)));
        }
        return null;
    }

    private static String gpu(String lib) {
        if (lib.matches("ati[ogc].*|amd.*|radeon.*|.*amdgpu.*|aticfx.*|libvulkan_radeon.*")) return "AMD";
        if (lib.matches("nv.*|.*nvidia.*")) return "NVIDIA";
        if (lib.matches("ig[0-9a-z]*icd(32|64)\\.dll|igc(32|64)\\.dll|igvk(32|64)\\.dll|iris_dri.*|i965_dri.*|igd.*"
                + "|libvulkan_intel.*")) {
            return "Intel";
        }
        if (lib.matches(".*(mesa|osmesa|_dri\\.so|libgallium|libllvm).*")) return "Linux (Mesa)";
        if (lib.contains("moltenvk") || lib.contains("applegpu") || lib.equals("glengine") || lib.startsWith("libgl.dylib")
                || lib.startsWith("libglprogrammability")) {
            return "macOS";
        }
        if (lib.matches(".*(gl4es|mobileglues|mali|adreno|libgles).*")) return "phone or tablet";
        return null;
    }

    private static String overlay(String lib) {
        if (lib.startsWith("rtsshooks")) return "RivaTuner / MSI Afterburner";
        if (lib.startsWith("discordhook")) return "Discord";
        if (lib.startsWith("gameoverlayrenderer")) return "Steam";
        if (lib.startsWith("nvspcap")) return "NVIDIA's overlay (ShadowPlay)";
        if (lib.startsWith("graphics-hook") || lib.contains("obs")) return "OBS";
        if (lib.startsWith("renderdoc")) return "RenderDoc";
        return null;
    }

    private static String shaders(String lower) {
        return lower.contains("iris") || lower.contains("oculus") || lower.contains("optifine")
                ? " Shaders were running, and they push the driver hardest." : "";
    }

    private static String driverFix(String vendor) {
        return switch (vendor) {
            case "AMD" -> "Update the AMD driver from amd.com (choose a clean install). If it keeps happening, try "
                    + "without shaders, or an older AMD driver: some versions crash Minecraft.";
            case "NVIDIA" -> "Update the NVIDIA driver from nvidia.com (choose \"clean installation\"). If it keeps "
                    + "happening, try without shaders and turn off NVIDIA's overlay.";
            case "Intel" -> "Update the Intel graphics driver from intel.com. Laptops with two graphics chips: make "
                    + "Java use the stronger one (Windows Settings > Display > Graphics).";
            default -> "Update your graphics driver. If it keeps happening, try without shaders.";
        };
    }
}
