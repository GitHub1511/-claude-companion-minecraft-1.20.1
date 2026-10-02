package com.claudecompanion.client;

import com.claudecompanion.ClaudeCompanion;
import com.claudecompanion.Config;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Writer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Neural voices that run on your own computer as small local servers the mod starts and stops with the game.
 *
 * ttsEngine "csm": Sesame CSM-1B is the main voice. Kokoro (Emma) also starts and speaks while Sesame installs or
 * loads, and stays as the voice if Sesame can't run; once Sesame is ready, Kokoro shuts down to free memory.
 * ttsEngine "kokoro": Kokoro only.
 */
public final class NeuralVoice {
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
	private static final ExecutorService SYNTH = Executors.newFixedThreadPool(2, r -> {
		Thread t = new Thread(r, "Claude-Voice-Synth");
		t.setDaemon(true);
		return t;
	});
	private static final Pattern SENTENCE = Pattern.compile("[^.!?;:]+[.!?;:]*");

	/** One local voice server. */
	static final class Engine {
		final String label;
		final String script;
		final String folder;
		volatile URI url;
		volatile boolean ready;
		volatile boolean failed;
		volatile Process process;

		Engine(String label, String script, String folder) {
			this.label = label;
			this.script = script;
			this.folder = folder;
		}
	}

	static final Engine SESAME = new Engine("Sesame", "csm_server.py", "csm");
	static final Engine KOKORO = new Engine("Kokoro", "kokoro_server.py", "kokoro");

	private static volatile SourceDataLine playing;
	private static volatile boolean stop;
	private static boolean started;

	private NeuralVoice() {}

	/** The voice to use right now, or null to fall back to the OS voice. */
	static Engine active() {
		if (SESAME.ready) return SESAME;
		if (KOKORO.ready) return KOKORO;
		return null;
	}

	public static boolean isReady() {
		return active() != null;
	}

	// ------------------------------------------------------------------ startup

	public static synchronized void startInBackground() {
		if (started) return;
		started = true;
		Config cfg = Config.get();
		String engine = cfg.ttsEngine == null ? "" : cfg.ttsEngine.toLowerCase(Locale.ROOT);
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			stopEngine(SESAME);
			stopEngine(KOKORO);
		}));

		KOKORO.url = URI.create(cfg.kokoroUrl);
		launch(KOKORO, List.of("--model", cfg.kokoroModel, "--voice", cfg.kokoroVoice), null);

		if (engine.equals("csm") || engine.equals("sesame")) {
			SESAME.url = URI.create(cfg.csmUrl);
			List<String> args = new ArrayList<>(List.of("--prompt", cfg.csmVoicePrompt, "--device", cfg.csmDevice,
					"--finetune", cfg.csmFinetune == null ? "" : cfg.csmFinetune.trim()));
			if (cfg.csmVoicePromptText != null && !cfg.csmVoicePromptText.isBlank()) args.addAll(List.of("--prompt-text", cfg.csmVoicePromptText));
			if (cfg.torchIndexUrl != null && !cfg.torchIndexUrl.isBlank()) args.addAll(List.of("--torch-index", cfg.torchIndexUrl));
			launch(SESAME, args, cfg.huggingfaceToken);
		}
	}

	private static void launch(Engine e, List<String> extraArgs, String hfToken) {
		Thread t = new Thread(() -> {
			try {
				run(e, extraArgs, hfToken);
				if (e == SESAME) {
					Hud.chat("§6[Claude voice]§r Switched to the Sesame voice.");
					stopEngine(KOKORO); // free the memory; Sesame takes over
				}
			} catch (Exception ex) {
				e.failed = true;
				ClaudeCompanion.LOG.error("{} voice failed", e.label, ex);
				String fallback = e == SESAME ? (KOKORO.failed ? "your computer's built-in voice" : "the Kokoro voice") : "your computer's built-in voice";
				if (!"EXPLAINED".equals(ex.getMessage())) {
					Hud.chat("§6[Claude voice]§r " + e.label + " isn't available (" + ex.getMessage() + "). Using " + fallback + ".");
				}
			}
		}, "Claude-" + e.label + "-Start");
		t.setDaemon(true);
		t.start();
	}

	private static void run(Engine e, List<String> extraArgs, String hfToken) throws Exception {
		if (alive(e.url)) {
			e.ready = true;
			ClaudeCompanion.LOG.info("Using the {} server already running at {}", e.label, e.url);
			return;
		}
		Config cfg = Config.get();
		String host = e.url.getHost() == null ? "" : e.url.getHost();
		if (!cfg.voiceAutoStart || !(host.equals("127.0.0.1") || host.equals("localhost"))) {
			throw new IllegalStateException("nothing answering at " + e.url);
		}

		Path dir = FabricLoader.getInstance().getConfigDir().resolve("claudecompanion").resolve(e.folder);
		Files.createDirectories(dir);
		Path script = dir.resolve(e.script);
		List<String> bundled = new ArrayList<>(List.of(e.script));
		if (e == SESAME) bundled.add("voice_prompt_warm.wav");
		for (String name : bundled) {
			try (InputStream in = NeuralVoice.class.getResourceAsStream("/claudecompanion/" + name)) {
				if (in == null) throw new IllegalStateException(name + " is missing from the mod jar");
				Files.copy(in, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
			}
		}
		List<String> python = findPython();
		if (python == null) throw new IllegalStateException("Python 3.10 to 3.12 isn't installed (python.org, tick \"Add to PATH\")");

		List<String> cmd = new ArrayList<>(python);
		cmd.addAll(List.of(script.toString(), "--port", Integer.toString(e.url.getPort() > 0 ? e.url.getPort() : 80), "--dir", dir.toString()));
		cmd.addAll(extraArgs);
		ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true);
		pb.environment().put("PYTHONUNBUFFERED", "1");
		pb.environment().put("PYTHONIOENCODING", "utf-8");
		if (hfToken != null && !hfToken.isBlank()) pb.environment().put("HF_TOKEN", hfToken.trim());
		Process p = pb.start();
		e.process = p;
		pipeOutput(e, p, dir.resolve("server.log"));

		long deadline = System.currentTimeMillis() + 90 * 60_000L; // first run can download several GB
		while (System.currentTimeMillis() < deadline) {
			if (!p.isAlive()) {
				// Exit codes 3 to 5 mean the server already told the player what to do (token, terms, prompt file).
				throw new IllegalStateException(p.exitValue() >= 3 && p.exitValue() <= 5 ? "EXPLAINED"
						: "its setup stopped, see config/claudecompanion/" + e.folder + "/server.log");
			}
			if (alive(e.url)) {
				e.ready = true;
				return;
			}
			Thread.sleep(2000);
		}
		throw new IllegalStateException("setup timed out");
	}

	private static void pipeOutput(Engine e, Process p, Path log) {
		Thread t = new Thread(() -> {
			try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
				 Writer w = Files.newBufferedWriter(log, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
				String line;
				while ((line = r.readLine()) != null) {
					w.write(line);
					w.write(System.lineSeparator());
					w.flush();
					// Once Sesame has taken over, Kokoro's chatter is just noise.
					if (line.startsWith("STATUS ") && !(e == KOKORO && SESAME.ready)) {
						Hud.chat("§6[Claude voice: " + e.label + "]§r " + line.substring(7));
					}
				}
			} catch (Exception ignored) {
			}
		}, "Claude-" + e.label + "-Log");
		t.setDaemon(true);
		t.start();
	}

	private static void stopEngine(Engine e) {
		e.ready = false;
		Process p = e.process;
		if (p == null) return;
		try {
			p.getOutputStream().close(); // the server exits when its stdin closes
		} catch (Exception ignored) {
		}
		p.destroy();
		e.process = null;
	}

	private static List<String> findPython() {
		Config cfg = Config.get();
		List<List<String>> candidates = new ArrayList<>();
		if (cfg.pythonCommand != null && !cfg.pythonCommand.isBlank()) {
			candidates.add(Arrays.asList(cfg.pythonCommand.trim().split("\\s+")));
		}
		boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
		for (String v : new String[]{"3.12", "3.11", "3.10", "3.13"}) {
			candidates.add(windows ? List.of("py", "-" + v) : List.of("python" + v));
		}
		candidates.add(List.of(windows ? "python" : "python3"));
		candidates.add(List.of(windows ? "python3" : "python"));
		List<String> fallback = null;
		for (List<String> c : candidates) {
			int v = pythonVersion(c);
			if (v >= 310 && v <= 312) return c;
			if (v >= 310 && fallback == null) fallback = c;
		}
		return fallback;
	}

	private static int pythonVersion(List<String> cmd) {
		try {
			List<String> full = new ArrayList<>(cmd);
			full.addAll(List.of("-c", "import sys; print(sys.version_info[0] * 100 + sys.version_info[1])"));
			Process p = new ProcessBuilder(full).redirectErrorStream(true).start();
			String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
			p.waitFor();
			return p.exitValue() == 0 ? Integer.parseInt(out) : -1;
		} catch (Exception e) {
			return -1;
		}
	}

	private static boolean alive(URI speechUrl) {
		try {
			HTTP.send(HttpRequest.newBuilder(speechUrl.resolve("/health")).timeout(Duration.ofSeconds(2)).GET().build(),
					HttpResponse.BodyHandlers.discarding());
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	// ------------------------------------------------------------------ speaking

	/** Speaks sentence by sentence, preparing the next one while the current one plays. Blocks until done. */
	public static void speak(String text) throws Exception {
		Engine e = active();
		if (e == null) throw new IllegalStateException("no neural voice ready");
		stop = false;
		List<String> parts = sentences(text);
		if (parts.isEmpty()) return;
		CompletableFuture<byte[]> next = synthAsync(e, parts.get(0));
		for (int i = 0; i < parts.size() && !stop; i++) {
			byte[] wav = next.get();
			next = i + 1 < parts.size() ? synthAsync(e, parts.get(i + 1)) : null;
			play(wav);
		}
	}

	public static void interrupt() {
		stop = true;
		SourceDataLine line = playing;
		if (line != null) {
			line.stop();
			line.flush();
		}
	}

	static List<String> sentences(String text) {
		List<String> out = new ArrayList<>();
		Matcher m = SENTENCE.matcher(text);
		StringBuilder pending = new StringBuilder();
		while (m.find()) {
			pending.append(m.group());
			if (pending.toString().trim().length() >= 25) {
				out.add(pending.toString().trim());
				pending.setLength(0);
			}
		}
		if (!pending.toString().isBlank()) out.add(pending.toString().trim());
		return out;
	}

	private static CompletableFuture<byte[]> synthAsync(Engine e, String sentence) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				return synth(e, sentence);
			} catch (Exception ex) {
				throw new RuntimeException(ex);
			}
		}, SYNTH);
	}

	private static byte[] synth(Engine e, String sentence) throws Exception {
		Config cfg = Config.get();
		JsonObject body = new JsonObject();
		body.addProperty("model", e == KOKORO ? "kokoro" : "csm-1b");
		body.addProperty("input", sentence);
		body.addProperty("voice", cfg.kokoroVoice);
		body.addProperty("speed", Math.max(0.5, Math.min(2.0, cfg.ttsSpeed)));
		body.addProperty("response_format", "wav");
		HttpRequest req = HttpRequest.newBuilder(e.url)
				.timeout(Duration.ofSeconds(120))
				.header("content-type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
				.build();
		HttpResponse<byte[]> res = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
		if (res.statusCode() != 200) {
			throw new IllegalStateException(e.label + " HTTP " + res.statusCode() + ": " + new String(res.body(), StandardCharsets.UTF_8));
		}
		return res.body();
	}

	private static void play(byte[] wav) throws Exception {
		try (AudioInputStream ais = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wav))) {
			AudioFormat fmt = ais.getFormat();
			SourceDataLine line = AudioSystem.getSourceDataLine(fmt);
			line.open(fmt);
			line.start();
			playing = line;
			try {
				byte[] buf = new byte[4096];
				int n;
				while (!stop && (n = ais.read(buf)) > 0) line.write(buf, 0, n);
				if (!stop) line.drain();
			} finally {
				playing = null;
				line.stop();
				line.close();
			}
		}
	}
}
