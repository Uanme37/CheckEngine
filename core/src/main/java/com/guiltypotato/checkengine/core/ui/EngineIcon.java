package com.guiltypotato.checkengine.core.ui;

/**
 * The engine light, 14 x 9 pixels (tools/dev/EngineLight.java bakes it into a texture with its soft halo): the same
 * in the game and in the window.
 */
public final class EngineIcon {
    public static final int WIDTH = 14, HEIGHT = 9;
    /** Halo around the icon, in icon pixels. */
    public static final int MARGIN = 3;
    /** Texture pixels per icon pixel. */
    public static final int SCALE = 12;
    /** One cell of the texture: red, amber and green, one under the other. */
    public static final int CELL_W = (WIDTH + 2 * MARGIN) * SCALE, CELL_H = (HEIGHT + 2 * MARGIN) * SCALE;
    public static final int RED = 0, AMBER = 1, GREEN = 2;

    private EngineIcon() {}
}
