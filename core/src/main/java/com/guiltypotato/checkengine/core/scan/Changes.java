package com.guiltypotato.checkengine.core.scan;

import java.util.ArrayList;
import java.util.List;

/**
 * What changed in a pack since the last launch that worked, and which root problems those changes explain.
 *
 * @param since   when the pack last worked, e.g. "2026-10-08T12:30"
 * @param changes every added, removed or updated mod (and Minecraft or loader updates)
 */
public record Changes(String since, List<Change> changes) {
    public static final String CODE = "pack-changed";
    public static final String DOWNGRADE_CODE = "downgraded-mod";
    /** How many changes to name per kind before saying "and N more". */
    private static final int LIST_LIMIT = 10;

    /** CONFIG: a config file changed since the pack last worked ({@code name} is its path). */
    public enum Type { ADDED, REMOVED, UPDATED, CONFIG }

    /**
     * @param id   mod id ("minecraft" / "loader" for those)
     * @param from old version, or null when added
     * @param to   new version, or null when removed
     * @param file the jar (the new one, or the removed one), or null
     */
    public record Change(Type type, String id, String name, String from, String to, String file) {
        /** "added Create 6.0.4", "removed Create 6.0.4", "updated Create 5.1.0 -> 6.0.4" */
        public String describe() {
            return switch (type) {
                case ADDED -> "added " + name + " " + to;
                case REMOVED -> "removed " + name + " " + from;
                case UPDATED -> (downgrade() ? "downgraded " : "updated ") + name + " " + from + " -> " + to;
                case CONFIG -> to + " config " + name;
            };
        }

        /** An "update" to an older version: worlds saved with the newer one may not load right. */
        public boolean downgrade() {
            // not Check Engine itself: putting an older test build back is no risk to worlds
            return type == Type.UPDATED && from != null && to != null && !id.equals("loader") && !id.equals("checkengine")
                    && com.guiltypotato.checkengine.core.version.ModVersion.parse(to)
                            .compareTo(com.guiltypotato.checkengine.core.version.ModVersion.parse(from)) < 0;
        }
    }

    public boolean isEmpty() {
        return changes.isEmpty();
    }

    /** "2026-10-08T12:30" -> "2026-10-08 12:30" */
    public String when() {
        return since.replace('T', ' ');
    }

    /** Lines like "Added: A 1.0, B 2.0" for each kind that has something. */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        line(sb, "Added", Type.ADDED, false);
        line(sb, "Removed", Type.REMOVED, false);
        line(sb, "Updated", Type.UPDATED, false);
        line(sb, "Downgraded", Type.UPDATED, true);
        line(sb, "Configs", Type.CONFIG, false);
        return sb.toString().strip();
    }

    private void line(StringBuilder sb, String label, Type type, boolean downgrades) {
        List<Change> list = changes.stream().filter(c -> c.type() == type && c.downgrade() == downgrades).toList();
        if (list.isEmpty()) return;
        sb.append(label).append(": ");
        for (int i = 0; i < list.size(); i++) {
            if (i == LIST_LIMIT) {
                sb.append(", and ").append(list.size() - LIST_LIMIT).append(" more");
                break;
            }
            Change c = list.get(i);
            if (i > 0) sb.append(", ");
            if (type == Type.CONFIG) {
                sb.append(c.name()).append(" (").append(c.to()).append(')');
                continue;
            }
            sb.append(c.name()).append(' ').append(type == Type.UPDATED ? c.from() + " -> " + c.to()
                    : type == Type.ADDED ? c.to() : c.from());
        }
        sb.append('\n');
    }

    /**
     * One warning per mod (or Minecraft) that went back to an older version since the pack last worked: a world saved
     * with the newer one can lose blocks and items, or not open.
     */
    public List<Finding> downgradeFindings() {
        return changes.stream().filter(Change::downgrade).map(c -> new Finding(Finding.Severity.WARNING,
                DOWNGRADE_CODE, c.name() + " went back to an older version",
                c.name() + " is now " + c.to() + ", but the pack last worked with " + c.from() + " (" + when() + "). "
                        + "Worlds saved with the newer version can lose blocks and items from it, or crash.",
                "If that was on purpose, back up your worlds before opening them. If not, put " + c.name() + " "
                        + c.from() + " back.", c.file() == null ? List.of() : List.of(c.file()))).toList();
    }

    /** For reports and screens: a heads-up listing the changes. */
    public Finding toFinding() {
        return new Finding(Finding.Severity.WARNING, CODE, "Changed since this pack last worked (" + when() + ")",
                summary(), "If the pack broke after these changes, start with them: undo the newest one first.",
                List.of());
    }

    /**
     * Adds "Changed since it last worked: you removed Create 6.0.4." to every root problem a change is part of
     * (the change is to a mod the problem is about, or to one of its files). Those problems also move to the top.
     */
    public List<RootProblem> annotate(List<RootProblem> roots) {
        List<RootProblem> linked = new ArrayList<>();
        List<RootProblem> rest = new ArrayList<>();
        for (RootProblem r : roots) {
            List<String> related = changes.stream()
                    .filter(c -> r.ids().contains(c.id()) || (c.file() != null && r.files().contains(c.file())))
                    .map(Change::describe).toList();
            if (related.isEmpty()) {
                rest.add(r);
                continue;
            }
            linked.add(new RootProblem(r.code(), r.title(), r.detail() + "\nChanged since it last worked (" + when()
                    + "): you " + String.join(", ", related) + ".", r.fix(), r.certainty(), r.affected(), r.files(),
                    r.ids()));
        }
        linked.addAll(rest);
        return linked;
    }
}
