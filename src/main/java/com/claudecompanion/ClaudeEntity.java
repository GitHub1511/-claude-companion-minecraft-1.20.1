package com.claudecompanion;

import com.claudecompanion.agent.WorldEvents;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.LookAroundGoal;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.world.World;

import java.util.UUID;

/** Claude's body: a player-shaped mob the agent drives through tools. */
public class ClaudeEntity extends PathAwareEntity {
	private UUID followId;
	private long lastHurtLog;

	public ClaudeEntity(EntityType<? extends ClaudeEntity> type, World world) {
		super(type, world);
		this.setCustomName(Text.literal("Claude"));
		this.setCustomNameVisible(true);
		this.setPersistent();
		this.setInvulnerable(Config.get().invulnerable);
	}

	public static DefaultAttributeContainer.Builder createAttributes() {
		return MobEntity.createMobAttributes()
				.add(EntityAttributes.GENERIC_MAX_HEALTH, 40.0)
				.add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.32)
				.add(EntityAttributes.GENERIC_FOLLOW_RANGE, 96.0)
				.add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 5.0);
	}

	@Override
	protected void initGoals() {
		this.goalSelector.add(0, new SwimGoal(this));
		this.goalSelector.add(2, new MeleeAttackGoal(this, 1.3, true));
		this.goalSelector.add(8, new LookAtEntityGoal(this, PlayerEntity.class, 10.0f));
		this.goalSelector.add(9, new LookAroundGoal(this));
	}

	public void follow(PlayerEntity player) {
		this.setTarget(null);
		this.followId = player.getUuid();
	}

	public void stay() {
		this.followId = null;
		this.setTarget(null);
		this.getNavigation().stop();
	}

	public String describeMode() {
		LivingEntity target = this.getTarget();
		if (target != null) return "attacking " + target.getName().getString();
		if (followId != null) {
			PlayerEntity p = this.getWorld().getPlayerByUuid(followId);
			return "following " + (p != null ? p.getName().getString() : "a player");
		}
		if (!this.getNavigation().isIdle()) return "walking";
		return "standing still";
	}

	@Override
	public void tick() {
		super.tick();
		if (this.getWorld().isClient || followId == null || this.getTarget() != null || this.age % 10 != 0) return;
		PlayerEntity p = this.getWorld().getPlayerByUuid(followId);
		if (p == null) return;
		double d = this.squaredDistanceTo(p);
		if (d > 40 * 40) {
			this.requestTeleport(p.getX() + 1.0, p.getY(), p.getZ() + 1.0);
		} else if (d > 4 * 4) {
			this.getNavigation().startMovingTo(p, 1.15);
		} else if (d < 2.5 * 2.5) {
			this.getNavigation().stop();
		}
	}

	@Override
	public boolean damage(DamageSource source, float amount) {
		boolean hurt = super.damage(source, amount);
		long now = System.currentTimeMillis();
		if (hurt && now - lastHurtLog > 3000) {
			lastHurtLog = now;
			WorldEvents.add("Claude (you) took damage from " + source.getName());
		}
		return hurt;
	}

	@Override
	public boolean canImmediatelyDespawn(double distanceSquared) {
		return false;
	}
}
