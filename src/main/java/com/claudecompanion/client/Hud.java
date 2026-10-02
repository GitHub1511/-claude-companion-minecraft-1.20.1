package com.claudecompanion.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

/** Thread-safe helpers for putting text on the player's screen. */
public final class Hud {
	private Hud() {}

	public static void chat(Text text) {
		MinecraftClient c = MinecraftClient.getInstance();
		c.execute(() -> c.inGameHud.getChatHud().addMessage(text));
	}

	public static void chat(String text) {
		chat(Text.literal(text));
	}

	public static void actionBar(String text) {
		MinecraftClient c = MinecraftClient.getInstance();
		c.execute(() -> {
			if (c.player != null) c.player.sendMessage(Text.literal(text), true);
		});
	}
}
