package com.claudecompanion;

import com.claudecompanion.agent.ClaudeAgent;
import com.claudecompanion.agent.WorldEvents;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ClaudeCompanion implements ModInitializer {
	public static final String MOD_ID = "claudecompanion";
	public static final Logger LOG = LoggerFactory.getLogger("Claude Companion");

	public static final EntityType<ClaudeEntity> CLAUDE = Registry.register(
			Registries.ENTITY_TYPE,
			new Identifier(MOD_ID, "claude"),
			FabricEntityTypeBuilder.<ClaudeEntity>create(SpawnGroup.MISC, ClaudeEntity::new)
					.dimensions(EntityDimensions.fixed(0.6f, 1.8f))
					.trackRangeBlocks(96)
					.trackedUpdateRate(2)
					.build());

	private static UUID claudeId;
	/** Set by /claude dismiss so Claude doesn't pop back the moment you speak. */
	public static volatile boolean dismissed;
	private static final Map<UUID, Long> lastPlayerHurtLog = new HashMap<>();

	@Override
	public void onInitialize() {
		Config.load();
		FabricDefaultAttributeRegistry.register(CLAUDE, ClaudeEntity.createAttributes());
		registerCommands();
		registerEvents();
		LOG.info("Claude Companion loaded. Model: {}", Config.get().model);
	}

	// ------------------------------------------------------------------ finding and summoning

	public static ClaudeEntity findClaude(MinecraftServer server) {
		if (server == null) return null;
		if (claudeId != null) {
			for (ServerWorld w : server.getWorlds()) {
				Entity e = w.getEntity(claudeId);
				if (e instanceof ClaudeEntity c && c.isAlive()) return c;
			}
		}
		for (ServerWorld w : server.getWorlds()) {
			for (Entity e : w.iterateEntities()) {
				if (e instanceof ClaudeEntity c && c.isAlive()) {
					claudeId = c.getUuid();
					return c;
				}
			}
		}
		return null;
	}

	/** Spawns Claude two blocks in front of the player, or brings the existing Claude over. Server thread only. */
	public static ClaudeEntity summon(ServerPlayerEntity player) {
		dismissed = false;
		ServerWorld world = player.getServerWorld();
		Vec3d look = player.getRotationVector();
		double x = player.getX() + look.x * 2.0;
		double y = player.getY();
		double z = player.getZ() + look.z * 2.0;

		ClaudeEntity c = findClaude(player.getServer());
		if (c != null && c.getWorld() != world) {
			c.discard();
			c = null;
		}
		if (c == null) {
			c = CLAUDE.create(world);
			if (c == null) return null;
			c.refreshPositionAndAngles(x, y, z, player.getYaw() + 180f, 0f);
			world.spawnEntity(c);
			claudeId = c.getUuid();
		} else {
			c.requestTeleport(x, y, z);
		}
		c.setInvulnerable(Config.get().invulnerable);
		return c;
	}

	// ------------------------------------------------------------------ commands

	private static void registerCommands() {
		CommandRegistrationCallback.EVENT.register((dispatcher, access, env) -> dispatcher.register(
				CommandManager.literal("claude")
						.then(CommandManager.literal("summon").executes(ctx -> {
							ServerPlayerEntity p = ctx.getSource().getPlayer();
							if (p == null) return 0;
							summon(p);
							ClaudeAgent.hear(p.getName().getString(),
									p.getName().getString() + " just summoned you next to them. Say hi in one short sentence.", "system");
							return 1;
						}))
						.then(CommandManager.literal("dismiss").executes(ctx -> {
							ClaudeEntity c = findClaude(ctx.getSource().getServer());
							if (c != null) c.discard();
							dismissed = true;
							ctx.getSource().sendFeedback(() -> Text.literal("Claude dismissed. /claude summon brings them back."), false);
							return 1;
						}))
						.then(CommandManager.literal("ask")
								.then(CommandManager.argument("message", StringArgumentType.greedyString()).executes(ctx -> {
									ClaudeAgent.hear(ctx.getSource().getName(), StringArgumentType.getString(ctx, "message"), "chat");
									return 1;
								})))
						.then(CommandManager.literal("follow").executes(ctx -> {
							ServerPlayerEntity p = ctx.getSource().getPlayer();
							ClaudeEntity c = findClaude(ctx.getSource().getServer());
							if (p != null && c != null) c.follow(p);
							return 1;
						}))
						.then(CommandManager.literal("stay").executes(ctx -> {
							ClaudeEntity c = findClaude(ctx.getSource().getServer());
							if (c != null) c.stay();
							return 1;
						}))
						.then(CommandManager.literal("listen").executes(ctx -> {
							ClaudeAgent.listenToggle.run();
							return 1;
						}))
						.then(CommandManager.literal("reset").executes(ctx -> {
							ClaudeAgent.reset();
							ctx.getSource().sendFeedback(() -> Text.literal("Claude's conversation memory was cleared."), false);
							return 1;
						}))
						.then(CommandManager.literal("reload").executes(ctx -> {
							Config.load();
							ctx.getSource().sendFeedback(() -> Text.literal("Reloaded config/claudecompanion.json"), false);
							return 1;
						}))
		));
	}

	// ------------------------------------------------------------------ what Claude notices

	private static void registerEvents() {
		ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
			String text = message.getContent().getString();
			if (Config.get().respondToAllChat || text.toLowerCase().contains("claude")) {
				ClaudeAgent.hear(sender.getName().getString(), text, "chat");
			} else {
				WorldEvents.add(sender.getName().getString() + " said in chat: " + text);
			}
		});

		PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) ->
				WorldEvents.add(player.getName().getString() + " broke " + Registries.BLOCK.getId(state.getBlock()).getPath()
						+ " at " + pos.toShortString()));

		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
			if (entity instanceof ServerPlayerEntity p) {
				long now = System.currentTimeMillis();
				Long last = lastPlayerHurtLog.get(p.getUuid());
				if (last == null || now - last > 4000) {
					lastPlayerHurtLog.put(p.getUuid(), now);
					Entity attacker = source.getAttacker();
					WorldEvents.add(p.getName().getString() + " got hurt by " + source.getName()
							+ (attacker != null ? " (" + attacker.getName().getString() + ")" : "")
							+ ", health now about " + Math.max(0, Math.round(p.getHealth() - amount)) + "/20");
				}
			}
			return true;
		});

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			ClaudeAgent.reset();
			claudeId = null;
		});
	}
}
