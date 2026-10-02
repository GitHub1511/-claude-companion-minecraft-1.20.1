package com.claudecompanion;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Settings live in .minecraft/config/claudecompanion.json (created on first launch). */
public final class Config {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static Config instance;

	// Claude API
	public String apiKey = "";
	/** Only needed for keys not tied to a workspace (sk-ant-usr-...). Looks like wrkspc_... (Console, Settings, Workspaces). */
	public String anthropicWorkspaceId = "";
	public String model = "claude-sonnet-5-5";
	public int maxTokens = 2048;
	public int maxToolSteps = 25;
	public String extraPersonality = "";

	// Voice in (offline, Vosk)
	public boolean voiceEnabled = true;
	public boolean requireWakeWord = false;
	public String voskModelUrl = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip";
	public String voskModelPath = "";

	// Voice out
	public boolean ttsEnabled = true;
	/**
	 * "csm" = Sesame CSM-1B (most natural; wants an NVIDIA GPU and a Hugging Face token; Kokoro covers until it's ready),
	 * "kokoro" = Kokoro only (natural, light, no account), "system" = your OS's built-in voice.
	 */
	public String ttsEngine = "csm";

	// Sesame CSM-1B
	/** Free token from huggingface.co (Settings, Access Tokens), on an account that accepted the terms at huggingface.co/sesame/csm-1b. */
	public String huggingfaceToken = "";
	/** Hugging Face repo of a decoder-only CSM-1B fine-tune applied on top of the base model. Blank = plain CSM-1B. */
	public String csmFinetune = "Tachyeon/csm-1b-conversational-finetuned";
	/**
	 * ex04 = a real clip of the Expresso speaker the fine-tune learned (default, best match),
	 * warm = original upbeat American female voice bundled with the mod,
	 * conversational_a / conversational_b = Sesame's examples, or a path to your own WAV.
	 */
	public String csmVoicePrompt = "ex04";
	/** Exact transcript of your own WAV, only needed when csmVoicePrompt is a file path. */
	public String csmVoicePromptText = "";
	/** auto, cuda or cpu. */
	public String csmDevice = "auto";
	public String csmUrl = "http://127.0.0.1:8890/v1/audio/speech";
	/** Leave blank to auto-pick (CUDA 12.6, or 12.8 for RTX 50-series). */
	public String torchIndexUrl = "";

	// Kokoro
	public String kokoroVoice = "bf_emma";
	/** kokoro-v1.0.onnx is best and fastest on most PCs. kokoro-v1.0.int8.onnx is a smaller download but slower. */
	public String kokoroModel = "kokoro-v1.0.onnx";
	public String kokoroUrl = "http://127.0.0.1:8880/v1/audio/speech";
	/** Let the mod install and run the local voice servers itself. */
	public boolean voiceAutoStart = true;
	/** Leave blank to auto-detect. Example: "py -3.12" on Windows, "/opt/homebrew/bin/python3.12" on Mac. */
	public String pythonCommand = "";
	public double ttsSpeed = 1.0;
	/** Only used by the system voice fallback. */
	public String ttsVoice = "";

	// Behaviour
	public boolean respondToAllChat = true;
	public boolean autoSummon = true;
	public boolean invulnerable = true;
	public int maxBlocksPerCall = 32768;

	public static Config get() {
		if (instance == null) load();
		return instance;
	}

	public static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("claudecompanion.json");
	}

	public static synchronized void load() {
		Path p = file();
		Config c = null;
		try {
			if (Files.exists(p)) {
				try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
					c = GSON.fromJson(r, Config.class);
				}
			}
		} catch (Exception e) {
			ClaudeCompanion.LOG.error("Could not read {}, using defaults", p, e);
		}
		instance = c != null ? c : new Config();
		save();
	}

	public static synchronized void save() {
		try {
			Files.createDirectories(file().getParent());
			try (Writer w = Files.newBufferedWriter(file(), StandardCharsets.UTF_8)) {
				GSON.toJson(instance, w);
			}
		} catch (Exception e) {
			ClaudeCompanion.LOG.error("Could not write config", e);
		}
	}

	/** Config value first, then the ANTHROPIC_API_KEY environment variable. */
	public static String apiKey() {
		String k = get().apiKey;
		if (k == null || k.isBlank()) k = System.getenv("ANTHROPIC_API_KEY");
		return k == null ? "" : k.trim();
	}
}
