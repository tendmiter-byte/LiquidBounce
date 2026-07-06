/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2026 CCBlueX
 *
 * LiquidBounce is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LiquidBounce is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LiquidBounce. If not, see <https://www.gnu.org/licenses/>.
 */

package net.ccbluex.liquidbounce.utils.input;

import net.ccbluex.liquidbounce.integration.screen.ScreenManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;

public final class TextInputContext {

    private TextInputContext() {
    }

    public static boolean shouldSuppressFullscreenShortcut() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.gui == null) {
            return false;
        }

        Screen screen = mc.gui.screen();
        if (screen == null) {
            return false;
        }

        if (ScreenManager.isClientScreen(screen)) {
            return true;
        }

        GuiEventListener focused = screen.getFocused();

        while (focused instanceof net.minecraft.client.gui.components.events.ContainerEventHandler container) {
            GuiEventListener child = container.getFocused();
            if (child == null || child == focused) {
                break;
            }
            focused = child;
        }

        return focused instanceof EditBox editBox && editBox.canConsumeInput();
    }

}
