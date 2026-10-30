package com.guiltypotato.checkengine.ui;

import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * A plain button that says how far its screen has faded it in. The title screen fades its buttons in the first time
 * it opens (and sets that on every button it has, this one too), so whatever is drawn over the button can wait for it.
 */
public class FadeButton extends Button {
    public FadeButton(int x, int y, int width, int height, Component message, OnPress onPress) {
        super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
    }

    /** 0 = not shown yet, 1 = fully faded in. */
    public float fade() {
        return alpha;
    }
}
