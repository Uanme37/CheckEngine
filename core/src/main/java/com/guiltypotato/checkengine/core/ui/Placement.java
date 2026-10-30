package com.guiltypotato.checkengine.core.ui;

import java.util.List;

/**
 * Where to put a small square button next to existing ones without covering any: right of the anchor first, then
 * further right, then left of it. No game code, so it can move into a shared UI library.
 */
public final class Placement {
    /** A widget's rectangle. */
    public record Box(int x, int y, int w, int h) {
        boolean overlaps(Box o) {
            return x < o.x + o.w && o.x < x + w && y < o.y + o.h && o.y < y + h;
        }
    }

    private Placement() {}

    /**
     * @param anchor  the button to sit beside
     * @param taken   every widget already on the screen (the anchor too)
     * @param size    the new button's width and height
     * @param right   room it needs right of it too (a caption), kept free like the button
     * @param below   room it needs under it too
     * @param gap     space between buttons
     * @param screenW the screen's width
     * @return where it goes, or null when nothing near the anchor is free
     */
    public static Box beside(Box anchor, List<Box> taken, int size, int right, int below, int gap, int screenW) {
        int y = anchor.y + (anchor.h - size) / 2;
        for (int x = anchor.x + anchor.w + gap; x + size + right <= screenW - 2; x += size + gap) {
            Box b = new Box(x, y, size, size);
            if (free(new Box(b.x, b.y, size + right, size + below), taken)) return b;
        }
        for (int x = anchor.x - gap - size; x >= 2; x -= size + gap) {
            Box b = new Box(x, y, size, size);
            if (free(new Box(b.x, b.y, size + right, size + below), taken)) return b;
        }
        return null;
    }

    /**
     * A spot in a corner, stepping along the edge past widgets already there (another mod's corner button):
     * "bottom_left", "bottom_right", "top_left", "top_right", or "x,y" for an exact spot. Null when the corner is full
     * or the text isn't one of those.
     */
    public static Box corner(String where, List<Box> taken, int size, int right, int gap, int screenW, int screenH) {
        String w = where == null ? "" : where.strip().toLowerCase(java.util.Locale.ROOT);
        if (w.matches("\\d+\\s*,\\s*\\d+")) {
            String[] xy = w.split(",");
            int x = Math.min(Integer.parseInt(xy[0].strip()), Math.max(0, screenW - size - right));
            int y = Math.min(Integer.parseInt(xy[1].strip()), Math.max(0, screenH - size));
            return new Box(x, y, size, size);
        }
        boolean top = w.startsWith("top_"), left = w.endsWith("_left");
        if (!w.equals("bottom_left") && !w.equals("bottom_right") && !w.equals("top_left") && !w.equals("top_right")) {
            return null;
        }
        // Line up with the corner buttons already there: the one nearest the corner sets the row, and the new one sits
        // a gap after the last of them, so it looks like part of the same row.
        int band = size + 8;
        Box nearest = null;
        for (Box b : taken) {
            boolean inBand = top ? b.y <= 8 : b.y + b.h >= screenH - band;
            boolean sameSide = left ? b.x < screenW / 2 : b.x + b.w > screenW / 2;
            if (!inBand || !sameSide || b.h > 2 * size) continue;
            if (nearest == null || (left ? b.x < nearest.x : b.x + b.w > nearest.x + nearest.w)) nearest = b;
        }
        int y = nearest != null ? nearest.y + (nearest.h - size) / 2 : top ? 2 : screenH - size - 2;
        int x = left ? 2 : screenW - 2 - size - right;
        for (int step = 0; step < 64; step++) {
            if (x < 2 || x + size + right > screenW - 2) return null;
            Box want = new Box(x, y, size + right, size);
            Box hit = null;
            for (Box t : taken) if (want.overlaps(t)) hit = hit == null ? t : left ? (t.x + t.w > hit.x + hit.w ? t : hit) : (t.x < hit.x ? t : hit);
            if (hit == null) return new Box(x, y, size, size);
            x = left ? hit.x + hit.w + gap : hit.x - gap - size - right;
        }
        return null;
    }

    private static boolean free(Box b, List<Box> taken) {
        for (Box t : taken) if (b.overlaps(t)) return false;
        return true;
    }
}
