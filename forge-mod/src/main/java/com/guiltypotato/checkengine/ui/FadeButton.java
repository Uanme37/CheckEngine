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

    /** Whether the screen drew it yet: 1.20.1's title screen draws no buttons for its first second. */
    private boolean drawn;

    @Override
    protected void renderWidget(net.minecraft.client.gui.GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        drawn = true;
        super.renderWidget(g, mouseX, mouseY, partialTick);
    }

    /** 0 = not shown yet, 1 = fully faded in. */
    public float fade() {
        return drawn ? alpha : 0;
    }
}
