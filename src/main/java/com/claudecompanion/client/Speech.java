package com.claudecompanion.client;

import com.claudecompanion.ClaudeCompanion;
import com.claudecompanion.Config;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Claude's voice. Sesame CSM-1B or Kokoro by default (see NeuralVoice). Until one is ready, or if it fails, falls back
 * to the text to speech built into your operating system: Windows speech, macOS "say", or espeak on Linux.
 */
public final class Speech {
	private static final ExecutorService TTS = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "Claude-Voice-Out");
		t.setDaemon(true);
		return t;
	});
	private static final AtomicInteger pending = new AtomicInteger();
	private static volatile long lastFinished;
	private static volatile Process current;

	private Speech() {}

	/** True while Claude is talking (and a moment after), so the microphone ignores Claude's own voice. */
	public static boolean isSpeaking() {
		return pending.get() > 0 || System.currentTimeMillis() - lastFinished < 700;
	}

	public static void speak(String text) {
		if (!Config.get().ttsEnabled || text.isBlank()) return;
		pending.incrementAndGet();
		TTS.submit(() -> {
			try {
				speakBlocking(text);
			} catch (Exception e) {
				ClaudeCompanion.LOG.warn("Text to speech failed: {}", e.toString());
			} finally {
				lastFinished = System.currentTimeMillis();
				pending.decrementAndGet();
			}
		});
	}

	/** Cut Claude off mid-sentence. */
	public static void shutUp() {
		NeuralVoice.interrupt();
		Process p = current;
		if (p != null) p.destroy();
	}

	/** Strip things that sound bad when read aloud. */
	public static String clean(String raw) {
		if (raw == null) return "";
		String s = raw.replaceAll("[*_`#>]", "")
				.replaceAll("§.", "")
				.replaceAll("[\\x{1F000}-\\x{1FFFF}\\x{2600}-\\x{27BF}]", "")
				.replaceAll("\\s+", " ");
		return s.trim();
	}

	private static void speakBlocking(String text) throws Exception {
		if (!"system".equalsIgnoreCase(Config.get().ttsEngine) && NeuralVoice.isReady()) {
			try {
				NeuralVoice.speak(text);
				return;
			} catch (Exception e) {
				ClaudeCompanion.LOG.warn("Neural voice failed, using the system voice for this line: {}", e.toString());
			}
		}
		speakSystem(text);
	}

	private static void speakSystem(String text) throws Exception {
		Config cfg = Config.get();
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		double speed = Math.max(0.5, Math.min(2.0, cfg.ttsSpeed));
		String voice = cfg.ttsVoice == null ? "" : cfg.ttsVoice.trim();
		List<String> cmd = new ArrayList<>();

		if (os.contains("win")) {
			int rate = (int) Math.round((speed - 1.0) * 10);
			String select = voice.isEmpty() ? "" : "try { $s.SelectVoice('" + voice.replace("'", "''") + "') } catch {}; ";
			cmd.add("powershell");
			cmd.add("-NoProfile");
			cmd.add("-NonInteractive");
			cmd.add("-Command");
			cmd.add("[Console]::InputEncoding = [System.Text.Encoding]::UTF8; Add-Type -AssemblyName System.Speech; "
					+ "$s = New-Object System.Speech.Synthesis.SpeechSynthesizer; " + select
					+ "$s.Rate = " + rate + "; $s.Speak([Console]::In.ReadToEnd())");
		} else if (os.contains("mac")) {
			cmd.add("say");
			cmd.add("-r");
			cmd.add(Integer.toString((int) Math.round(190 * speed)));
			if (!voice.isEmpty()) {
				cmd.add("-v");
				cmd.add(voice);
			}
			cmd.add("-f");
			cmd.add("-");
		} else {
			cmd.add("espeak");
			cmd.add("-s");
			cmd.add(Integer.toString((int) Math.round(170 * speed)));
			if (!voice.isEmpty()) {
				cmd.add("-v");
				cmd.add(voice);
			}
			cmd.add("--stdin");
		}

		Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
		current = p;
		try (OutputStream in = p.getOutputStream()) {
			in.write(text.getBytes(StandardCharsets.UTF_8));
		}
		p.getInputStream().transferTo(OutputStream.nullOutputStream());
		if (!p.waitFor(90, TimeUnit.SECONDS)) p.destroyForcibly();
		current = null;
	}
}
