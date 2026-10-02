package com.claudecompanion.client;

import com.claudecompanion.ClaudeCompanion;
import com.claudecompanion.Config;
import com.claudecompanion.agent.ClaudeAgent;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

public class ClaudeCompanionClient implements ClientModInitializer {
	private static KeyBinding listenKey;

	@Override
	public void onInitializeClient() {
		EntityRendererRegistry.register(ClaudeCompanion.CLAUDE, ClaudeRenderer::new);

		listenKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.claudecompanion.listen", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K, "category.claudecompanion"));
		ClaudeAgent.listenToggle = VoiceInput::toggle;

		// Start the local voice servers while the game loads (first run installs them).
		if (Config.get().ttsEnabled && !"system".equalsIgnoreCase(Config.get().ttsEngine)) NeuralVoice.startInBackground();

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (listenKey.wasPressed()) VoiceInput.toggle();
		});

		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			if (client.getServer() == null) {
				Hud.chat("§6[Claude Companion]§r Claude only lives in singleplayer worlds (or the LAN host's world), so it's off on this server.");
				return;
			}
			if (Config.apiKey().isEmpty()) {
				Hud.chat("§6[Claude Companion]§r Add your Claude API key to config/claudecompanion.json (\"apiKey\"), then run /claude reload.");
			}
			if (Config.get().voiceEnabled) VoiceInput.start();
			if (SelfTest.enabled()) SelfTest.startOnce();
		});

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			VoiceInput.mute();
			Speech.shutUp();
		});
	}
}
