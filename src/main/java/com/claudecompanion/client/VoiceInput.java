package com.claudecompanion.client;

import com.claudecompanion.ClaudeCompanion;
import com.claudecompanion.Config;
import com.claudecompanion.agent.ClaudeAgent;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.TargetDataLine;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Always-on microphone. Audio is transcribed locally by Vosk (offline, free, nothing leaves your
 * computer until a finished sentence is sent to Claude). The speech model downloads itself the first time.
 */
public final class VoiceInput {
	private static final Set<String> FILLER = Set.of("", "the", "a", "huh", "uh", "um", "hmm", "oh", "ah", "and", "i", "it", "but", "so", "yeah");
	private static final Pattern WAKE = Pattern.compile("\\b(claude|cloud|clod|claud|clawed|clyde)\\b", Pattern.CASE_INSENSITIVE);

	private static volatile boolean listening;
	private static volatile Thread worker;
	private static volatile boolean running;
	private static String lastPartial = "";

	private VoiceInput() {}

	public static synchronized void start() {
		listening = true;
		if (worker != null && worker.isAlive()) return;
		running = true;
		worker = new Thread(VoiceInput::run, "Claude-Voice-In");
		worker.setDaemon(true);
		worker.start();
	}

	public static void toggle() {
		if (worker == null || !worker.isAlive()) {
			start();
			return;
		}
		listening = !listening;
		Hud.actionBar(listening ? "§aClaude is listening" : "§cClaude's microphone is muted (press K to unmute)");
	}

	public static void mute() {
		listening = false;
	}

	private static void run() {
		Path model = ensureModel();
		if (model == null) return;
		try {
			LibVosk.setLogLevel(LogLevel.WARNINGS);
		} catch (Throwable ignored) {
		}
		AudioFormat fmt = new AudioFormat(16000f, 16, 1, true, false);
		try (Model m = new Model(model.toString()); Recognizer rec = new Recognizer(m, 16000f)) {
			TargetDataLine line = (TargetDataLine) AudioSystem.getLine(new DataLine.Info(TargetDataLine.class, fmt));
			line.open(fmt, 8192);
			line.start();
			Hud.chat("§a[Claude Companion]§r Microphone on. Just talk. Press K to mute or unmute.");
			byte[] buf = new byte[3200];
			boolean dirty = false;
			while (running) {
				int n = line.read(buf, 0, buf.length);
				if (n <= 0) continue;
				if (!listening || Speech.isSpeaking()) {
					if (dirty) {
						rec.reset();
						dirty = false;
					}
					continue;
				}
				dirty = true;
				if (rec.acceptWaveForm(buf, n)) {
					dirty = false;
					handle(field(rec.getResult(), "text"));
				} else {
					String partial = field(rec.getPartialResult(), "partial");
					if (!partial.isEmpty() && !partial.equals(lastPartial)) {
						lastPartial = partial;
						Hud.actionBar("§7(hearing) " + partial);
					}
				}
			}
			line.close();
		} catch (Throwable e) {
			ClaudeCompanion.LOG.error("Microphone / speech recognition failed", e);
			Hud.chat("§c[Claude Companion]§r Voice input stopped: " + e.getMessage()
					+ ". Check that Minecraft can use your microphone. You can still type in chat.");
		}
	}

	private static void handle(String text) {
		String t = text.trim();
		if (FILLER.contains(t.toLowerCase(Locale.ROOT))) return;
		if (Config.get().requireWakeWord && !WAKE.matcher(t).find()) return;
		MinecraftClient c = MinecraftClient.getInstance();
		String me = c.getSession().getUsername();
		Hud.chat("§7(you said) " + t);
		Hud.actionBar("");
		lastPartial = "";
		Speech.shutUp();
		ClaudeAgent.hear(me, t, "voice");
	}

	private static String field(String json, String key) {
		try {
			return JsonParser.parseString(json).getAsJsonObject().get(key).getAsString().trim();
		} catch (Exception e) {
			return "";
		}
	}

	// ------------------------------------------------------------------ speech model

	private static Path ensureModel() {
		Config cfg = Config.get();
		if (cfg.voskModelPath != null && !cfg.voskModelPath.isBlank()) return Path.of(cfg.voskModelPath);
		Path base = FabricLoader.getInstance().getConfigDir().resolve("claudecompanion");
		Path dir = base.resolve("vosk-model");
		if (Files.isDirectory(dir.resolve("am")) || Files.isDirectory(dir.resolve("conf"))) return dir;
		try {
			Files.createDirectories(base);
			Hud.chat("§6[Claude Companion]§r Downloading the offline speech model (about 40 MB, first time only)...");
			Path zip = base.resolve("vosk-model.zip");
			HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).connectTimeout(Duration.ofSeconds(30)).build();
			HttpResponse<Path> res = http.send(HttpRequest.newBuilder(URI.create(cfg.voskModelUrl)).timeout(Duration.ofMinutes(10)).build(),
					HttpResponse.BodyHandlers.ofFile(zip));
			if (res.statusCode() != 200) throw new IllegalStateException("download failed with HTTP " + res.statusCode());

			Path tmp = base.resolve("vosk-model-unpacking");
			deleteTree(tmp);
			Files.createDirectories(tmp);
			try (InputStream in = Files.newInputStream(zip); ZipInputStream zin = new ZipInputStream(in)) {
				ZipEntry e;
				while ((e = zin.getNextEntry()) != null) {
					String name = e.getName();
					int slash = name.indexOf('/');
					String inner = slash >= 0 ? name.substring(slash + 1) : name; // drop the zip's top folder
					if (inner.isEmpty()) continue;
					Path out = tmp.resolve(inner).normalize();
					if (!out.startsWith(tmp)) continue;
					if (e.isDirectory()) Files.createDirectories(out);
					else {
						Files.createDirectories(out.getParent());
						Files.copy(zin, out, StandardCopyOption.REPLACE_EXISTING);
					}
				}
			}
			deleteTree(dir);
			Files.move(tmp, dir);
			Files.deleteIfExists(zip);
			Hud.chat("§a[Claude Companion]§r Speech model ready.");
			return dir;
		} catch (Exception e) {
			ClaudeCompanion.LOG.error("Could not get the speech model", e);
			Hud.chat("§c[Claude Companion]§r Couldn't download the speech model (" + e.getMessage()
					+ "). Download " + cfg.voskModelUrl + ", unzip it, and set voskModelPath in config/claudecompanion.json.");
			return null;
		}
	}

	private static void deleteTree(Path p) throws Exception {
		if (!Files.exists(p)) return;
		try (var walk = Files.walk(p)) {
			for (Path q : walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) Files.deleteIfExists(q);
		}
	}
}
