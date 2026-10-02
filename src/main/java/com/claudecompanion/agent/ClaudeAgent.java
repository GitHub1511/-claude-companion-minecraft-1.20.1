package com.claudecompanion.agent;

import com.claudecompanion.ClaudeCompanion;
import com.claudecompanion.ClaudeEntity;
import com.claudecompanion.Config;
import com.claudecompanion.client.Hud;
import com.claudecompanion.client.Speech;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.server.MinecraftServer;
import net.minecraft.text.Text;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * The brain. Every utterance becomes a turn: send the conversation plus a fresh situation report
 * to the Claude API, speak whatever Claude says, run whatever tools it calls, repeat until it's done.
 */
public final class ClaudeAgent {
	private static final String API_URL = "https://api.anthropic.com/v1/messages";
	private static final int MAX_HISTORY = 60;
	private static final Pattern STOP = Pattern.compile("^(?:hey\\s+)?(?:claude[,\\s]+)?(stop|wait|hold on|freeze|cancel|never ?mind)\\b", Pattern.CASE_INSENSITIVE);

	private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "Claude-Agent");
		t.setDaemon(true);
		return t;
	});
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

	/** Only touched from the agent thread (reset() is queued onto it). */
	private static final List<JsonObject> HISTORY = new ArrayList<>();
	private static volatile boolean cancel;

	/** The client entrypoint plugs the microphone toggle in here. */
	public static Runnable listenToggle = () -> {};

	private ClaudeAgent() {}

	/** Hand Claude something to respond to. Safe to call from any thread. channel: voice, chat or system. */
	public static void hear(String speaker, String text, String channel) {
		String t = text == null ? "" : text.trim();
		if (t.isEmpty()) return;
		if (!"system".equals(channel) && STOP.matcher(t).find() && t.split("\\s+").length <= 5) {
			cancel = true;
			stopBody();
		}
		EXEC.submit(() -> {
			cancel = false;
			try {
				turn(speaker, t, channel);
			} catch (Throwable e) {
				ClaudeCompanion.LOG.error("Claude turn failed", e);
				notice("Claude ran into a problem: " + e.getMessage());
			} finally {
				Hud.actionBar("");
			}
		});
	}

	public static void reset() {
		cancel = true;
		EXEC.submit(() -> {
			HISTORY.clear();
			WorldEvents.clear();
		});
	}

	// ------------------------------------------------------------------ the loop

	private static void turn(String speaker, String text, String channel) throws Exception {
		Config cfg = Config.get();
		String key = Config.apiKey();
		if (key.isEmpty()) {
			notice("No Claude API key yet. Put it in config/claudecompanion.json as \"apiKey\", then run /claude reload.");
			return;
		}
		if (cfg.autoSummon && !ClaudeCompanion.dismissed) Tools.ensureClaude(speaker);

		String label = switch (channel) {
			case "voice" -> speaker + " (said out loud)";
			case "system" -> "[system]";
			default -> speaker + " (typed in chat)";
		};
		String situation;
		try {
			situation = Tools.situation(speaker);
		} catch (Exception e) {
			situation = "unavailable: " + e.getMessage();
		}
		addUserText(label + ": " + text + "\n\n<situation>\n" + situation + "\n</situation>");

		Hud.actionBar("§7Claude is thinking...");
		for (int step = 0; step < cfg.maxToolSteps && !cancel; step++) {
			JsonObject resp = call(key);
			JsonArray content = resp.has("content") ? resp.getAsJsonArray("content") : new JsonArray();
			if (content.isEmpty()) break;

			JsonObject assistant = new JsonObject();
			assistant.addProperty("role", "assistant");
			assistant.add("content", content);
			HISTORY.add(assistant);

			List<JsonObject> uses = new ArrayList<>();
			for (JsonElement el : content) {
				JsonObject block = el.getAsJsonObject();
				String type = block.get("type").getAsString();
				if (type.equals("text")) say(block.get("text").getAsString());
				else if (type.equals("tool_use")) uses.add(block);
			}
			String stop = resp.has("stop_reason") && !resp.get("stop_reason").isJsonNull() ? resp.get("stop_reason").getAsString() : "";
			if (!stop.equals("tool_use") || uses.isEmpty()) break;

			JsonArray results = new JsonArray();
			for (JsonObject use : uses) {
				String name = use.get("name").getAsString();
				String id = use.get("id").getAsString();
				JsonObject input = use.has("input") && use.get("input").isJsonObject() ? use.getAsJsonObject("input") : new JsonObject();
				if (cancel) {
					results.add(Tools.textResult(id, "Cancelled: the player told you to stop.", true));
				} else {
					Hud.actionBar("§7Claude: " + name.replace('_', ' ') + "...");
					results.add(Tools.execute(name, input, id, speaker));
				}
			}
			JsonObject user = new JsonObject();
			user.addProperty("role", "user");
			user.add("content", results);
			HISTORY.add(user);
		}
		trim();
	}

	/** Adds a user text block, merging into a trailing user message so roles always alternate. */
	private static void addUserText(String text) {
		JsonObject block = new JsonObject();
		block.addProperty("type", "text");
		block.addProperty("text", text);
		if (!HISTORY.isEmpty()) {
			JsonObject last = HISTORY.get(HISTORY.size() - 1);
			if (last.get("role").getAsString().equals("user")) {
				last.getAsJsonArray("content").add(block);
				return;
			}
		}
		JsonArray content = new JsonArray();
		content.add(block);
		JsonObject msg = new JsonObject();
		msg.addProperty("role", "user");
		msg.add("content", content);
		HISTORY.add(msg);
	}

	private static JsonObject call(String key) throws Exception {
		Config cfg = Config.get();
		JsonObject body = new JsonObject();
		body.addProperty("model", cfg.model);
		body.addProperty("max_tokens", cfg.maxTokens);

		JsonObject cache = new JsonObject();
		cache.addProperty("type", "ephemeral");
		JsonObject sys = new JsonObject();
		sys.addProperty("type", "text");
		sys.addProperty("text", Prompt.system());
		sys.add("cache_control", cache);
		JsonArray system = new JsonArray();
		system.add(sys);
		body.add("system", system);
		body.add("tools", Tools.DEFINITIONS);

		JsonArray messages = new JsonArray();
		HISTORY.forEach(messages::add);
		body.add("messages", messages);

		HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(API_URL))
				.timeout(Duration.ofSeconds(180))
				.header("x-api-key", key)
				.header("anthropic-version", "2023-06-01")
				.header("content-type", "application/json");
		// Keys that aren't tied to one workspace (sk-ant-usr-...) must say which workspace to bill.
		if (cfg.anthropicWorkspaceId != null && !cfg.anthropicWorkspaceId.isBlank()) {
			rb.header("anthropic-workspace-id", cfg.anthropicWorkspaceId.trim());
		}
		HttpRequest req = rb.POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();

		for (int attempt = 0; ; attempt++) {
			HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			int code = res.statusCode();
			if (code == 200) return JsonParser.parseString(res.body()).getAsJsonObject();
			boolean retryable = code == 429 || code == 500 || code == 502 || code == 503 || code == 529;
			if (!retryable || attempt >= 3) {
				String msg = res.body();
				try {
					msg = JsonParser.parseString(res.body()).getAsJsonObject().getAsJsonObject("error").get("message").getAsString();
				} catch (Exception ignored) {}
				if (msg.contains("anthropic-workspace-id")) {
					throw new RuntimeException("this API key isn't tied to a workspace. Easiest fix: in console.anthropic.com, Settings, API Keys, "
							+ "create a key in the Default workspace and use that. Or put your workspace ID (wrkspc_...) in anthropicWorkspaceId.");
				}
				if (code == 400) {
					// The conversation got out of shape somehow. Start fresh rather than failing on every message.
					HISTORY.clear();
				}
				throw new RuntimeException("API error " + code + ": " + msg);
			}
			Thread.sleep(1500L << attempt);
		}
	}

	/** Keep the conversation bounded: old screenshots become text, oldest turns fall off. */
	private static void trim() {
		for (int i = 0; i < HISTORY.size() - 4; i++) stripImages(HISTORY.get(i));
		while (HISTORY.size() > MAX_HISTORY) {
			HISTORY.remove(0);
			while (!HISTORY.isEmpty() && !startsWithPlainUserText(HISTORY.get(0))) HISTORY.remove(0);
		}
	}

	private static boolean startsWithPlainUserText(JsonObject msg) {
		if (!msg.get("role").getAsString().equals("user")) return false;
		JsonArray c = msg.getAsJsonArray("content");
		return !c.isEmpty() && c.get(0).getAsJsonObject().get("type").getAsString().equals("text");
	}

	private static void stripImages(JsonObject msg) {
		if (!msg.get("role").getAsString().equals("user")) return;
		for (JsonElement el : msg.getAsJsonArray("content")) {
			JsonObject block = el.getAsJsonObject();
			if (!block.get("type").getAsString().equals("tool_result")) continue;
			JsonElement inner = block.get("content");
			if (inner == null || !inner.isJsonArray()) continue;
			JsonArray replaced = new JsonArray();
			for (JsonElement part : inner.getAsJsonArray()) {
				if (part.getAsJsonObject().get("type").getAsString().equals("image")) {
					JsonObject t = new JsonObject();
					t.addProperty("type", "text");
					t.addProperty("text", "[older screenshot removed to save space]");
					replaced.add(t);
				} else {
					replaced.add(part);
				}
			}
			block.add("content", replaced);
		}
	}

	// ------------------------------------------------------------------ output

	private static void say(String raw) {
		String text = Speech.clean(raw);
		if (text.isEmpty()) return;
		ClaudeCompanion.LOG.info("[Claude says] {}", text);
		broadcast(Text.literal("§d<Claude>§r " + text));
		Speech.speak(text);
	}

	static void notice(String msg) {
		broadcast(Text.literal("§6[Claude Companion]§r " + msg));
	}

	private static void broadcast(Text text) {
		MinecraftServer server = MinecraftClient.getInstance().getServer();
		if (server != null) server.execute(() -> server.getPlayerManager().broadcast(text, false));
		else Hud.chat(text);
	}

	private static void stopBody() {
		MinecraftServer server = MinecraftClient.getInstance().getServer();
		if (server == null) return;
		server.execute(() -> {
			ClaudeEntity c = ClaudeCompanion.findClaude(server);
			if (c != null) c.stay();
		});
	}
}
