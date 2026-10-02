package com.claudecompanion.agent;

import com.claudecompanion.ClaudeCompanion;
import com.claudecompanion.ClaudeEntity;
import com.claudecompanion.Config;
import com.claudecompanion.client.Vision;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.command.argument.BlockArgumentParser;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandOutput;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Property;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.StringIdentifiable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Everything Claude can do in the world. World access always happens on the server thread. */
public final class Tools {
	private Tools() {}

	public static final JsonArray DEFINITIONS = JsonParser.parseString("""
			[
			 {"name":"look_around","description":"Detailed scan around you: your status, the player's status and crosshair target, every nearby creature and player with coordinates, and a census of block types nearby. Use when the situation summary isn't enough.",
			  "input_schema":{"type":"object","properties":{"radius":{"type":"integer","description":"Block scan radius 4 to 16, default 10"}}}},
			 {"name":"scan_area","description":"Top-down map of the terrain surface around a point: a letter grid of the top block in each column plus a grid of surface heights. Use before building to find flat ground and understand the layout.",
			  "input_schema":{"type":"object","properties":{"x":{"type":"integer"},"z":{"type":"integer"},"radius":{"type":"integer","description":"1 to 12, default 8"}},"required":["x","z"]}},
			 {"name":"get_blocks","description":"List every non-air block, with block states, inside a box (max 8000 cells). Use to inspect a structure or check your own build precisely.",
			  "input_schema":{"type":"object","properties":{"x1":{"type":"integer"},"y1":{"type":"integer"},"z1":{"type":"integer"},"x2":{"type":"integer"},"y2":{"type":"integer"},"z2":{"type":"integer"}},"required":["x1","y1","z1","x2","y2","z2"]}},
			 {"name":"see_view","description":"Screenshot of exactly what the player sees right now through their first-person camera, shaders and textures included. Use to actually look at the scene, judge how a build looks, or when the player says look at this.",
			  "input_schema":{"type":"object","properties":{}}},
			 {"name":"move_to","description":"Walk to a position with pathfinding. Walking happens in the background over time; call wait if you need to arrive first.",
			  "input_schema":{"type":"object","properties":{"x":{"type":"integer"},"y":{"type":"integer"},"z":{"type":"integer"}},"required":["x","y","z"]}},
			 {"name":"teleport_to","description":"Instantly move your body to a position. Use for long distances or places you can't walk to.",
			  "input_schema":{"type":"object","properties":{"x":{"type":"integer"},"y":{"type":"integer"},"z":{"type":"integer"}},"required":["x","y","z"]}},
			 {"name":"follow_player","description":"Keep following a player around until told otherwise.",
			  "input_schema":{"type":"object","properties":{"player":{"type":"string","description":"Player name. Defaults to whoever is talking to you."}}}},
			 {"name":"stop","description":"Stop walking, following and attacking. Stay where you are.",
			  "input_schema":{"type":"object","properties":{}}},
			 {"name":"wait","description":"Pause for a few seconds, for example to let yourself finish walking somewhere. Returns your status afterwards.",
			  "input_schema":{"type":"object","properties":{"seconds":{"type":"integer","description":"1 to 10"}},"required":["seconds"]}},
			 {"name":"place_blocks","description":"Place one or many blocks at exact world coordinates, creative style with no inventory needed. Any block id from vanilla or any installed mod works, with optional block states, for example oak_stairs[facing=east,half=bottom] or lantern[hanging=true]. Up to 4096 blocks per call. Replaces whatever is at those positions.",
			  "input_schema":{"type":"object","properties":{"blocks":{"type":"array","items":{"type":"object","properties":{"x":{"type":"integer"},"y":{"type":"integer"},"z":{"type":"integer"},"block":{"type":"string"}},"required":["x","y","z","block"]}}},"required":["blocks"]}},
			 {"name":"fill","description":"Fill a box with one block. mode fill makes it solid, hollow makes a shell and clears the inside to air, outline makes a shell and leaves the inside alone, keep only fills air. Use block air with mode fill to clear a space.",
			  "input_schema":{"type":"object","properties":{"x1":{"type":"integer"},"y1":{"type":"integer"},"z1":{"type":"integer"},"x2":{"type":"integer"},"y2":{"type":"integer"},"z2":{"type":"integer"},"block":{"type":"string"},"mode":{"type":"string","enum":["fill","hollow","outline","keep"]}},"required":["x1","y1","z1","x2","y2","z2","block"]}},
			 {"name":"break_blocks","description":"Mine blocks the way a player would, with particles and optionally item drops. Give either a list of positions or a box (x1..z2, max 4096 blocks).",
			  "input_schema":{"type":"object","properties":{"positions":{"type":"array","items":{"type":"object","properties":{"x":{"type":"integer"},"y":{"type":"integer"},"z":{"type":"integer"}},"required":["x","y","z"]}},"x1":{"type":"integer"},"y1":{"type":"integer"},"z1":{"type":"integer"},"x2":{"type":"integer"},"y2":{"type":"integer"},"z2":{"type":"integer"},"drop_items":{"type":"boolean","description":"Default false"}}}},
			 {"name":"hold_item","description":"Hold an item in your hand (any item id, for example diamond_sword, torch, a modded item).",
			  "input_schema":{"type":"object","properties":{"item":{"type":"string"}},"required":["item"]}},
			 {"name":"give_item","description":"Give items to a player.",
			  "input_schema":{"type":"object","properties":{"item":{"type":"string"},"count":{"type":"integer"},"player":{"type":"string","description":"Defaults to whoever is talking to you."}},"required":["item"]}},
			 {"name":"attack","description":"Chase and fight the nearest creature matching an entity id (zombie, minecraft:skeleton, a modded mob) or a name, within 32 blocks. Use stop to end the fight.",
			  "input_schema":{"type":"object","properties":{"target":{"type":"string"}},"required":["target"]}},
			 {"name":"run_command","description":"Run any Minecraft command with operator permissions, executed as you, so @s and ~ ~ ~ refer to you. Returns the command output. Use for anything the other tools don't cover: time, weather, effects, summoning, enchanting, gamerules, particles, sounds, structures, locate, and so on. Leave off the leading slash.",
			  "input_schema":{"type":"object","properties":{"command":{"type":"string"}},"required":["command"]},
			  "cache_control":{"type":"ephemeral"}}
			]
			""").getAsJsonArray();

	private static final Map<String, BlockState> STATE_CACHE = new HashMap<>();

	// ------------------------------------------------------------------ plumbing

	static MinecraftServer server() {
		return MinecraftClient.getInstance().getServer();
	}

	static <T> T onServer(Supplier<T> task) throws Exception {
		MinecraftServer srv = server();
		if (srv == null) throw new IllegalStateException("Claude Companion only works in a singleplayer world (or on the computer hosting a LAN world).");
		try {
			return srv.submit(task).get(30, TimeUnit.SECONDS);
		} catch (ExecutionException e) {
			Throwable cause = e.getCause() != null ? e.getCause() : e;
			if (cause instanceof Exception ex) throw ex;
			throw new RuntimeException(cause);
		}
	}

	static JsonObject textResult(String id, String text, boolean error) {
		JsonObject r = new JsonObject();
		r.addProperty("type", "tool_result");
		r.addProperty("tool_use_id", id);
		r.addProperty("content", text);
		if (error) r.addProperty("is_error", true);
		return r;
	}

	public static JsonObject execute(String name, JsonObject in, String id, String speaker) {
		try {
			if (name.equals("see_view")) {
				String jpeg = Vision.captureJpegBase64(1024);
				String cross = Vision.crosshair();
				JsonArray content = new JsonArray();
				JsonObject img = new JsonObject();
				img.addProperty("type", "image");
				JsonObject src = new JsonObject();
				src.addProperty("type", "base64");
				src.addProperty("media_type", "image/jpeg");
				src.addProperty("data", jpeg);
				img.add("source", src);
				content.add(img);
				JsonObject txt = new JsonObject();
				txt.addProperty("type", "text");
				txt.addProperty("text", "This is the player's first-person view right now. Crosshair: " + cross);
				content.add(txt);
				JsonObject r = new JsonObject();
				r.addProperty("type", "tool_result");
				r.addProperty("tool_use_id", id);
				r.add("content", content);
				return r;
			}
			if (name.equals("wait")) {
				int s = Math.max(1, Math.min(10, i(in, "seconds", 3)));
				Thread.sleep(s * 1000L);
				return textResult(id, "Waited " + s + "s.\n" + onServer(() -> status(server(), player(server(), speaker), claude())), false);
			}
			String out = onServer(() -> run(name, in, speaker));
			return textResult(id, out, false);
		} catch (Exception e) {
			String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
			return textResult(id, "Error: " + msg, true);
		}
	}

	private static String run(String name, JsonObject in, String speaker) {
		MinecraftServer srv = server();
		ServerPlayerEntity player = player(srv, speaker);
		ClaudeEntity claude = claude();
		ServerWorld world = claude != null ? (ServerWorld) claude.getWorld() : player != null ? player.getServerWorld() : srv.getOverworld();

		switch (name) {
			case "look_around":
				return lookAround(world, player, claude, i(in, "radius", 10));
			case "scan_area":
				return scanArea(world, req(in, "x"), req(in, "z"), i(in, "radius", 8));
			case "get_blocks":
				return getBlocks(world, box(in));
			case "move_to": {
				ClaudeEntity c = requireClaude(player);
				c.stay();
				int x = req(in, "x"), y = req(in, "y"), z = req(in, "z");
				boolean ok = c.getNavigation().startMovingTo(x + 0.5, y, z + 0.5, 1.2);
				double dist = Math.sqrt(c.squaredDistanceTo(x + 0.5, y, z + 0.5));
				return ok ? String.format(Locale.ROOT, "Walking there, %.0f blocks away (about %.0f seconds).", dist, dist / 4.5 + 0.5)
						: "Couldn't find a walking path there (too far or blocked). Try a closer waypoint or teleport_to.";
			}
			case "teleport_to": {
				ClaudeEntity c = requireClaude(player);
				c.stay();
				c.requestTeleport(req(in, "x") + 0.5, req(in, "y"), req(in, "z") + 0.5);
				return "Teleported. " + status(srv, player, c);
			}
			case "follow_player": {
				ClaudeEntity c = requireClaude(player);
				ServerPlayerEntity target = player(srv, s(in, "player", speaker));
				if (target == null) return "No such player online.";
				c.follow(target);
				return "Now following " + target.getName().getString() + ".";
			}
			case "stop": {
				ClaudeEntity c = requireClaude(player);
				c.stay();
				return "Stopped.";
			}
			case "place_blocks":
				return placeBlocks(world, claude, in.getAsJsonArray("blocks"));
			case "fill":
				return fill(world, claude, box(in), s(in, "block", "air"), s(in, "mode", "fill"));
			case "break_blocks":
				return breakBlocks(world, claude, in);
			case "hold_item": {
				ClaudeEntity c = requireClaude(player);
				Item item = item(s(in, "item", "air"));
				c.equipStack(EquipmentSlot.MAINHAND, new ItemStack(item));
				return "Now holding " + Registries.ITEM.getId(item) + ".";
			}
			case "give_item":
				return giveItem(srv, speaker, in);
			case "attack":
				return attack(world, requireClaude(player), s(in, "target", ""));
			case "run_command":
				return runCommand(srv, claude, s(in, "command", ""));
			default:
				return "Unknown tool " + name;
		}
	}

	// ------------------------------------------------------------------ situation report

	/** Called by the agent before every turn. Runs the world part on the server and the camera part on the client. */
	public static String situation(String speaker) throws Exception {
		String world = onServer(() -> {
			MinecraftServer srv = server();
			ServerPlayerEntity player = player(srv, speaker);
			ClaudeEntity claude = claude();
			StringBuilder sb = new StringBuilder(status(srv, player, claude));
			LivingEntity center = player != null ? player : claude;
			if (center != null) {
				List<Entity> near = nearby(center, 24);
				if (!near.isEmpty()) {
					sb.append("Nearby:");
					int n = 0;
					for (Entity e : near) {
						if (n++ >= 10) break;
						sb.append("\n - ").append(entityLine(e, center));
					}
					sb.append('\n');
				}
			}
			List<String> events = WorldEvents.drainNew(12);
			if (!events.isEmpty()) sb.append("Since you last spoke:\n - ").append(String.join("\n - ", events)).append('\n');
			return sb.toString();
		});
		return world + "Player's crosshair: " + Vision.crosshair();
	}

	public static void ensureClaude(String speaker) throws Exception {
		onServer(() -> {
			MinecraftServer srv = server();
			if (ClaudeCompanion.findClaude(srv) == null) {
				ServerPlayerEntity p = player(srv, speaker);
				if (p != null) {
					ClaudeCompanion.summon(p);
					WorldEvents.add("You (Claude) just appeared next to " + p.getName().getString());
				}
			}
			return null;
		});
	}

	private static String status(MinecraftServer srv, ServerPlayerEntity player, ClaudeEntity claude) {
		StringBuilder sb = new StringBuilder();
		if (claude != null) {
			sb.append(String.format(Locale.ROOT, "You (Claude): at %s, health %.0f/%.0f, holding %s, %s\n",
					pos(claude), claude.getHealth(), claude.getMaxHealth(), itemName(claude.getMainHandStack()), claude.describeMode()));
		} else {
			sb.append("You (Claude): not in the world right now.\n");
		}
		if (player != null) {
			ServerWorld w = player.getServerWorld();
			BlockPos bp = player.getBlockPos();
			sb.append(String.format(Locale.ROOT, "Player %s: at %s, facing %s (pitch %.0f), health %.0f/20, food %d/20, holding %s, dimension %s\n",
					player.getName().getString(), pos(player), player.getHorizontalFacing().asString(), player.getPitch(),
					player.getHealth(), player.getHungerManager().getFoodLevel(), itemName(player.getMainHandStack()),
					w.getRegistryKey().getValue()));
			long tod = w.getTimeOfDay() % 24000L;
			int hour = (int) ((tod / 1000 + 6) % 24);
			int minute = (int) ((tod % 1000) * 60 / 1000);
			String weather = w.isThundering() ? "thunderstorm" : w.isRaining() ? "raining" : "clear";
			String biome = w.getBiome(bp).getKey().map(k -> k.getValue().toString()).orElse("unknown");
			sb.append(String.format(Locale.ROOT, "World: day %d, %02d:%02d (%s), %s, biome %s\n",
					w.getTimeOfDay() / 24000L + 1, hour, minute, (tod < 12300 || tod > 23850) ? "daytime" : "night", weather, shortId(biome)));
		}
		return sb.toString();
	}

	private static String lookAround(ServerWorld w, ServerPlayerEntity player, ClaudeEntity claude, int radius) {
		int r = Math.max(4, Math.min(16, radius));
		StringBuilder sb = new StringBuilder(status(server(), player, claude));
		final LivingEntity center = claude != null ? claude : player;
		if (center == null) return sb.toString();

		List<Entity> near = nearby(center, r * 2);
		sb.append("Creatures and players within ").append(r * 2).append(" blocks of ").append(center == claude ? "you" : "the player").append(":\n");
		if (near.isEmpty()) sb.append(" none\n");
		int n = 0;
		for (Entity e : near) {
			if (n++ >= 30) break;
			sb.append(" - ").append(entityLine(e, center)).append('\n');
		}

		Map<String, Integer> counts = new HashMap<>();
		BlockPos c = center.getBlockPos();
		BlockPos.Mutable m = new BlockPos.Mutable();
		for (int dx = -r; dx <= r; dx++)
			for (int dy = -r; dy <= r; dy++)
				for (int dz = -r; dz <= r; dz++) {
					m.set(c.getX() + dx, c.getY() + dy, c.getZ() + dz);
					BlockState st = w.getBlockState(m);
					if (!st.isAir()) counts.merge(shortId(blockId(st)), 1, Integer::sum);
				}
		sb.append("Blocks within ").append(r).append(" (most common first): ");
		StringJoiner j = new StringJoiner(", ");
		counts.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(40)
				.forEach(e -> j.add(e.getKey() + " x" + e.getValue()));
		sb.append(j).append('\n');
		return sb.toString();
	}

	private static String scanArea(ServerWorld w, int cx, int cz, int radius) {
		int r = Math.max(1, Math.min(12, radius));
		String letters = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
		Map<String, Character> legend = new LinkedHashMap<>();
		StringBuilder grid = new StringBuilder();
		StringBuilder heights = new StringBuilder();
		for (int z = cz - r; z <= cz + r; z++) {
			StringJoiner hj = new StringJoiner(" ");
			for (int x = cx - r; x <= cx + r; x++) {
				int top = w.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z) - 1;
				String id = shortId(blockId(w.getBlockState(new BlockPos(x, top, z))));
				Character ch = legend.get(id);
				if (ch == null) {
					ch = legend.size() < letters.length() ? letters.charAt(legend.size()) : '?';
					legend.put(id, ch);
				}
				grid.append(ch);
				hj.add(Integer.toString(top));
			}
			grid.append('\n');
			heights.append(hj).append('\n');
		}
		StringJoiner lj = new StringJoiner(", ");
		legend.forEach((k, v) -> lj.add(v + "=" + k));
		return String.format(Locale.ROOT,
				"Top-down map centred on x=%d z=%d. Rows run north (z=%d) to south (z=%d), columns west (x=%d) to east (x=%d).%n"
						+ "Legend: %s%nSurface blocks:%n%sSurface y (the top solid block; stand or build at y+1):%n%s",
				cx, cz, cz - r, cz + r, cx - r, cx + r, lj, grid, heights);
	}

	private static String getBlocks(ServerWorld w, int[] b) {
		long vol = (long) (b[3] - b[0] + 1) * (b[4] - b[1] + 1) * (b[5] - b[2] + 1);
		if (vol > 8000) return "Box too big (" + vol + " cells). Max 8000; split it up.";
		StringBuilder sb = new StringBuilder();
		int count = 0;
		for (BlockPos p : BlockPos.iterate(b[0], b[1], b[2], b[3], b[4], b[5])) {
			BlockState st = w.getBlockState(p);
			if (st.isAir()) continue;
			if (++count > 1500) continue;
			sb.append(p.getX()).append(' ').append(p.getY()).append(' ').append(p.getZ()).append(' ').append(describe(st)).append('\n');
		}
		if (count == 0) return "All air.";
		if (count > 1500) sb.append("... and ").append(count - 1500).append(" more blocks (narrow the box to see them).\n");
		return count + " non-air blocks (x y z block):\n" + sb;
	}

	// ------------------------------------------------------------------ building

	private static String placeBlocks(ServerWorld w, ClaudeEntity claude, JsonArray arr) {
		if (arr == null || arr.isEmpty()) return "No blocks given.";
		if (arr.size() > 4096) return "Too many blocks in one call (" + arr.size() + "). Max 4096.";
		int placed = 0;
		Set<String> errors = new LinkedHashSet<>();
		BlockPos first = null;
		BlockState firstState = null;
		for (JsonElement el : arr) {
			try {
				JsonObject o = el.getAsJsonObject();
				BlockPos p = new BlockPos(req(o, "x"), req(o, "y"), req(o, "z"));
				BlockState st = parseBlock(s(o, "block", ""));
				if (w.isOutOfHeightLimit(p)) {
					errors.add("y=" + p.getY() + " is outside the build height");
					continue;
				}
				w.setBlockState(p, st, 3);
				placed++;
				if (first == null) {
					first = p;
					firstState = st;
				}
			} catch (Exception e) {
				errors.add(e.getMessage());
			}
		}
		animate(claude, first, firstState);
		StringBuilder sb = new StringBuilder("Placed " + placed + " of " + arr.size() + " blocks.");
		int n = 0;
		for (String e : errors) {
			if (n++ >= 6) break;
			sb.append("\nProblem: ").append(e);
		}
		return sb.toString();
	}

	private static String fill(ServerWorld w, ClaudeEntity claude, int[] b, String block, String mode) {
		long vol = (long) (b[3] - b[0] + 1) * (b[4] - b[1] + 1) * (b[5] - b[2] + 1);
		int max = Config.get().maxBlocksPerCall;
		if (vol > max) return "Box too big (" + vol + " blocks). Max " + max + " per call; split it up.";
		BlockState st = parseBlock(block);
		BlockState air = Blocks.AIR.getDefaultState();
		int changed = 0;
		for (BlockPos p : BlockPos.iterate(b[0], b[1], b[2], b[3], b[4], b[5])) {
			if (w.isOutOfHeightLimit(p)) continue;
			boolean shell = p.getX() == b[0] || p.getX() == b[3] || p.getY() == b[1] || p.getY() == b[4] || p.getZ() == b[2] || p.getZ() == b[5];
			BlockState target = st;
			if (mode.equals("hollow")) target = shell ? st : air;
			else if (mode.equals("outline")) target = shell ? st : null;
			else if (mode.equals("keep")) target = w.getBlockState(p).isAir() ? st : null;
			if (target != null && w.setBlockState(p, target, 3)) changed++;
		}
		animate(claude, new BlockPos(b[0], b[1], b[2]), st);
		return "Fill (" + mode + ") done, " + changed + " blocks changed.";
	}

	private static String breakBlocks(ServerWorld w, ClaudeEntity claude, JsonObject in) {
		boolean drop = in.has("drop_items") && in.get("drop_items").getAsBoolean();
		List<BlockPos> targets = new ArrayList<>();
		if (in.has("positions") && in.get("positions").isJsonArray()) {
			for (JsonElement el : in.getAsJsonArray("positions")) {
				JsonObject o = el.getAsJsonObject();
				targets.add(new BlockPos(req(o, "x"), req(o, "y"), req(o, "z")));
			}
		} else if (in.has("x1")) {
			int[] b = box(in);
			long vol = (long) (b[3] - b[0] + 1) * (b[4] - b[1] + 1) * (b[5] - b[2] + 1);
			if (vol > 4096) return "Box too big (" + vol + "). Max 4096; or use fill with air.";
			for (BlockPos p : BlockPos.iterate(b[0], b[1], b[2], b[3], b[4], b[5])) targets.add(p.toImmutable());
		} else {
			return "Give positions or a box.";
		}
		if (targets.size() > 4096) return "Too many positions. Max 4096.";
		int broken = 0;
		for (BlockPos p : targets) {
			if (w.getBlockState(p).isAir()) continue;
			if (broken < 256) {
				if (w.breakBlock(p, drop, claude)) broken++;
			} else if (w.setBlockState(p, Blocks.AIR.getDefaultState(), 3)) {
				broken++;
			}
		}
		if (claude != null && !targets.isEmpty()) {
			BlockPos p = targets.get(0);
			claude.getLookControl().lookAt(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5);
			claude.swingHand(Hand.MAIN_HAND);
		}
		return "Broke " + broken + " blocks.";
	}

	private static void animate(ClaudeEntity claude, BlockPos p, BlockState st) {
		if (claude == null || p == null) return;
		claude.getLookControl().lookAt(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5);
		if (st != null && !st.isAir()) claude.equipStack(EquipmentSlot.MAINHAND, new ItemStack(st.getBlock().asItem()));
		claude.swingHand(Hand.MAIN_HAND);
	}

	// ------------------------------------------------------------------ items, fighting, commands

	private static String giveItem(MinecraftServer srv, String speaker, JsonObject in) {
		ServerPlayerEntity p = player(srv, s(in, "player", speaker));
		if (p == null) return "No such player online.";
		Item item = item(s(in, "item", ""));
		int remaining = Math.max(1, Math.min(2304, i(in, "count", 1)));
		int total = remaining;
		while (remaining > 0) {
			int n = Math.min(remaining, item.getMaxCount());
			ItemStack stack = new ItemStack(item, n);
			if (!p.getInventory().insertStack(stack)) p.dropItem(stack, false);
			remaining -= n;
		}
		return "Gave " + total + " " + Registries.ITEM.getId(item) + " to " + p.getName().getString() + ".";
	}

	private static String attack(ServerWorld w, ClaudeEntity claude, String target) {
		String t = target.toLowerCase(Locale.ROOT).trim();
		if (t.isEmpty()) return "Say what to attack.";
		LivingEntity best = null;
		double bestD = Double.MAX_VALUE;
		for (Entity e : w.getOtherEntities(claude, claude.getBoundingBox().expand(32))) {
			if (!(e instanceof LivingEntity le) || !le.isAlive()) continue;
			String type = Registries.ENTITY_TYPE.getId(e.getType()).toString();
			String name = e.getName().getString().toLowerCase(Locale.ROOT);
			boolean match = type.equals(t) || type.endsWith(":" + t) || name.equals(t);
			if (e instanceof PlayerEntity && !name.equals(t)) match = false;
			double d = claude.squaredDistanceTo(e);
			if (match && d < bestD) {
				best = le;
				bestD = d;
			}
		}
		if (best == null) return "Nothing matching '" + target + "' within 32 blocks.";
		if (claude.getMainHandStack().isEmpty()) claude.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
		claude.stay();
		claude.setTarget(best);
		return String.format(Locale.ROOT, "Attacking %s, %.0f blocks away.", best.getName().getString(), Math.sqrt(bestD));
	}

	private static String runCommand(MinecraftServer srv, ClaudeEntity claude, String command) {
		String cmd = command.trim();
		if (cmd.startsWith("/")) cmd = cmd.substring(1);
		if (cmd.isEmpty()) return "Empty command.";
		StringBuilder out = new StringBuilder();
		CommandOutput collector = new CommandOutput() {
			@Override
			public void sendMessage(Text message) {
				out.append(message.getString()).append('\n');
			}

			@Override
			public boolean shouldReceiveFeedback() {
				return true;
			}

			@Override
			public boolean shouldTrackOutput() {
				return true;
			}

			@Override
			public boolean shouldBroadcastConsoleToOps() {
				return false;
			}
		};
		ServerCommandSource src = (claude != null ? claude.getCommandSource() : srv.getCommandSource()).withLevel(4).withOutput(collector);
		srv.getCommandManager().executeWithPrefix(src, cmd);
		String result = out.toString().trim();
		return result.isEmpty() ? "Command ran (no output)." : result;
	}

	// ------------------------------------------------------------------ helpers

	private static ClaudeEntity claude() {
		return ClaudeCompanion.findClaude(server());
	}

	private static ClaudeEntity requireClaude(ServerPlayerEntity player) {
		ClaudeEntity c = claude();
		if (c == null && player != null) c = ClaudeCompanion.summon(player);
		if (c == null) throw new IllegalStateException("You aren't in the world. Ask the player to run /claude summon.");
		return c;
	}

	private static ServerPlayerEntity player(MinecraftServer srv, String name) {
		if (srv == null) return null;
		ServerPlayerEntity p = name != null ? srv.getPlayerManager().getPlayer(name) : null;
		if (p == null && !srv.getPlayerManager().getPlayerList().isEmpty()) p = srv.getPlayerManager().getPlayerList().get(0);
		return p;
	}

	private static List<Entity> nearby(LivingEntity center, int range) {
		List<Entity> list = new ArrayList<>(center.getWorld().getOtherEntities(center, center.getBoundingBox().expand(range)));
		list.sort(Comparator.comparingDouble(e -> e.squaredDistanceTo(center)));
		return list;
	}

	private static String entityLine(Entity e, Entity from) {
		String type = shortId(Registries.ENTITY_TYPE.getId(e.getType()).toString());
		StringBuilder sb = new StringBuilder(type);
		if (e instanceof PlayerEntity || e.hasCustomName()) sb.append(" \"").append(e.getName().getString()).append('"');
		if (e instanceof ClaudeEntity) sb.append(" (you)");
		sb.append(" at ").append(pos(e)).append(String.format(Locale.ROOT, ", %.0f blocks away", Math.sqrt(e.squaredDistanceTo(from))));
		if (e instanceof LivingEntity le) sb.append(String.format(Locale.ROOT, ", health %.0f", le.getHealth()));
		if (e instanceof HostileEntity) sb.append(", hostile");
		return sb.toString();
	}

	static BlockState parseBlock(String s) {
		String key = s == null ? "" : s.trim();
		if (key.isEmpty()) throw new IllegalArgumentException("Missing block id");
		BlockState cached = STATE_CACHE.get(key);
		if (cached != null) return cached;
		try {
			BlockState st = BlockArgumentParser.block(Registries.BLOCK.getReadOnlyWrapper(), key, false).blockState();
			STATE_CACHE.put(key, st);
			return st;
		} catch (CommandSyntaxException e) {
			throw new IllegalArgumentException("Bad block '" + key + "': " + e.getMessage());
		}
	}

	private static Item item(String id) {
		Identifier ident = Identifier.tryParse(id.trim().toLowerCase(Locale.ROOT));
		if (ident == null || !Registries.ITEM.containsId(ident)) throw new IllegalArgumentException("Unknown item '" + id + "'");
		return Registries.ITEM.get(ident);
	}

	private static String blockId(BlockState st) {
		return Registries.BLOCK.getId(st.getBlock()).toString();
	}

	private static String describe(BlockState st) {
		String id = shortId(blockId(st));
		if (st.getEntries().isEmpty()) return id;
		StringJoiner j = new StringJoiner(",", "[", "]");
		for (Map.Entry<Property<?>, Comparable<?>> e : st.getEntries().entrySet()) {
			Comparable<?> v = e.getValue();
			j.add(e.getKey().getName() + "=" + (v instanceof StringIdentifiable si ? si.asString() : String.valueOf(v)));
		}
		return id + j;
	}

	private static String shortId(String id) {
		return id.startsWith("minecraft:") ? id.substring(10) : id;
	}

	private static String itemName(ItemStack stack) {
		return stack.isEmpty() ? "nothing" : shortId(Registries.ITEM.getId(stack.getItem()).toString());
	}

	private static String pos(Entity e) {
		return String.format(Locale.ROOT, "x=%.1f y=%.1f z=%.1f", e.getX(), e.getY(), e.getZ());
	}

	private static int[] box(JsonObject in) {
		int x1 = req(in, "x1"), y1 = req(in, "y1"), z1 = req(in, "z1"), x2 = req(in, "x2"), y2 = req(in, "y2"), z2 = req(in, "z2");
		return new int[]{Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2), Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2)};
	}

	private static int i(JsonObject o, String k, int def) {
		JsonElement e = o.get(k);
		if (e == null || e.isJsonNull()) return def;
		try {
			return (int) Math.floor(e.getAsDouble());
		} catch (Exception ex) {
			return def;
		}
	}

	private static int req(JsonObject o, String k) {
		if (!o.has(k) || o.get(k).isJsonNull()) throw new IllegalArgumentException("Missing '" + k + "'");
		return (int) Math.floor(o.get(k).getAsDouble());
	}

	private static String s(JsonObject o, String k, String def) {
		JsonElement e = o.get(k);
		return e == null || e.isJsonNull() || e.getAsString().isBlank() ? def : e.getAsString();
	}
}
