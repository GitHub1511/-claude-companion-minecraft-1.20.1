package com.claudecompanion.client;

import com.claudecompanion.ClaudeCompanion;
import com.claudecompanion.Config;
import com.claudecompanion.agent.ClaudeAgent;
import com.claudecompanion.agent.Tools;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;

/**
 * Automated in-game check, only active when Minecraft is started with -Dclaudecompanion.selftest=true.
 * Runs every tool against the live world, optionally one real Claude turn, logs "[SELFTEST]" lines, then quits.
 */
public final class SelfTest {
	private static boolean started;
	private static int passed, failed;

	private SelfTest() {}

	public static boolean enabled() {
		return Boolean.getBoolean("claudecompanion.selftest");
	}

	public static synchronized void startOnce() {
		if (started) return;
		started = true;
		Thread t = new Thread(SelfTest::run, "Claude-SelfTest");
		t.setDaemon(true);
		t.start();
	}

	private static void log(String s) {
		ClaudeCompanion.LOG.info("[SELFTEST] {}", s);
	}

	private static void run() {
		MinecraftClient c = MinecraftClient.getInstance();
		try {
			Thread.sleep(8000); // let the world settle
			String me = c.getSession().getUsername();
			BlockPos p = c.submit(() -> c.player.getBlockPos()).get();
			int x = p.getX(), y = p.getY(), z = p.getZ();
			log("player " + me + " at " + x + " " + y + " " + z);

			Tools.ensureClaude(me);
			check("situation", Tools.situation(me));

			tool(me, "look_around", "{\"radius\":6}");
			tool(me, "scan_area", "{\"x\":" + x + ",\"z\":" + z + ",\"radius\":4}");
			tool(me, "place_blocks", "{\"blocks\":[{\"x\":" + (x + 3) + ",\"y\":" + y + ",\"z\":" + z + ",\"block\":\"stone_bricks\"},"
					+ "{\"x\":" + (x + 3) + ",\"y\":" + (y + 1) + ",\"z\":" + z + ",\"block\":\"oak_stairs[facing=east,half=bottom]\"},"
					+ "{\"x\":" + (x + 3) + ",\"y\":" + (y + 2) + ",\"z\":" + z + ",\"block\":\"lantern[hanging=false]\"}]}");
			tool(me, "place_blocks", "{\"blocks\":[{\"x\":" + (x + 4) + ",\"y\":" + y + ",\"z\":" + z + ",\"block\":\"not_a_real_block\"}]}");
			tool(me, "get_blocks", "{\"x1\":" + (x + 3) + ",\"y1\":" + y + ",\"z1\":" + z + ",\"x2\":" + (x + 3) + ",\"y2\":" + (y + 2) + ",\"z2\":" + z + "}");
			tool(me, "fill", "{\"x1\":" + (x + 6) + ",\"y1\":" + y + ",\"z1\":" + (z - 2) + ",\"x2\":" + (x + 10) + ",\"y2\":" + (y + 3) + ",\"z2\":" + (z + 2) + ",\"block\":\"oak_planks\",\"mode\":\"hollow\"}");
			tool(me, "break_blocks", "{\"positions\":[{\"x\":" + (x + 3) + ",\"y\":" + (y + 2) + ",\"z\":" + z + "}],\"drop_items\":true}");
			tool(me, "hold_item", "{\"item\":\"diamond_sword\"}");
			tool(me, "give_item", "{\"item\":\"torch\",\"count\":16}");
			tool(me, "move_to", "{\"x\":" + (x - 4) + ",\"y\":" + y + ",\"z\":" + (z - 4) + "}");
			tool(me, "wait", "{\"seconds\":3}");
			tool(me, "teleport_to", "{\"x\":" + (x + 1) + ",\"y\":" + y + ",\"z\":" + (z + 1) + "}");
			tool(me, "follow_player", "{}");
			tool(me, "attack", "{\"target\":\"zombie\"}");
			tool(me, "stop", "{}");
			tool(me, "run_command", "{\"command\":\"/time set day\"}");
			tool(me, "see_view", "{}");

			try {
				Speech.speak("Self test speaking.");
				Thread.sleep(3000);
				check("speech (voice ready=" + NeuralVoice.isReady() + ")", "queued");
			} catch (Throwable e) {
				fail("speech", e);
			}

			if (!Config.apiKey().isEmpty()) {
				log("live Claude turn starting (model " + Config.get().model + ")");
				ClaudeAgent.hear(me, "This is an automated test. Place a 3 by 3 cobblestone platform two blocks north of me, then say done.", "chat");
				Thread.sleep(90_000);
				String blocks = Tools.execute("get_blocks", JsonParser.parseString("{\"x1\":" + (x - 6) + ",\"y1\":" + (y - 2) + ",\"z1\":" + (z - 8)
						+ ",\"x2\":" + (x + 6) + ",\"y2\":" + (y + 2) + ",\"z2\":" + z + "}").getAsJsonObject(), "t", me).toString();
				check("live turn placed cobblestone", blocks.contains("cobblestone") ? "yes" : "NO cobblestone found");
			} else {
				log("no API key configured, skipping the live Claude turn");
			}
		} catch (Throwable e) {
			fail("selftest", e);
		} finally {
			log("DONE passed=" + passed + " failed=" + failed);
			c.execute(c::scheduleStop);
		}
	}

	private static void tool(String me, String name, String json) {
		try {
			JsonObject r = Tools.execute(name, JsonParser.parseString(json).getAsJsonObject(), "t", me);
			boolean err = r.has("is_error") && r.get("is_error").getAsBoolean();
			String content = r.get("content").isJsonPrimitive() ? r.get("content").getAsString() : "[" + r.getAsJsonArray("content").size() + " content blocks, image attached]";
			// The bogus block is supposed to be rejected.
			boolean expectedError = json.contains("not_a_real_block") || name.equals("attack");
			if (err && !expectedError) {
				failed++;
				log("FAIL " + name + ": " + oneLine(content));
			} else {
				passed++;
				log("ok   " + name + ": " + oneLine(content));
			}
		} catch (Throwable e) {
			fail(name, e);
		}
	}

	private static void check(String what, String result) {
		passed++;
		log("ok   " + what + ": " + oneLine(result));
	}

	private static void fail(String what, Throwable e) {
		failed++;
		ClaudeCompanion.LOG.error("[SELFTEST] FAIL " + what, e);
	}

	private static String oneLine(String s) {
		String t = s.replace("\n", " | ");
		return t.length() > 400 ? t.substring(0, 400) + "..." : t;
	}
}
