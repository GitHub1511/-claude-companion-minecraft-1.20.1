package com.claudecompanion.client;

import com.claudecompanion.ClaudeCompanion;
import com.claudecompanion.ClaudeEntity;
import net.minecraft.client.render.entity.BipedEntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.util.Identifier;

/** Draws Claude with the normal player model and Claude's own skin. */
public class ClaudeRenderer extends BipedEntityRenderer<ClaudeEntity, PlayerEntityModel<ClaudeEntity>> {
	private static final Identifier SKIN = new Identifier(ClaudeCompanion.MOD_ID, "textures/entity/claude.png");

	public ClaudeRenderer(EntityRendererFactory.Context ctx) {
		super(ctx, new PlayerEntityModel<>(ctx.getPart(EntityModelLayers.PLAYER), false), 0.5f);
	}

	@Override
	public Identifier getTexture(ClaudeEntity entity) {
		return SKIN;
	}
}
