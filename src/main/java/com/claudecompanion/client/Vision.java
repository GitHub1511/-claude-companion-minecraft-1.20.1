package com.claudecompanion.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.Entity;
import net.minecraft.registry.Registries;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Claude's eyes: the player's camera and crosshair. */
public final class Vision {
	private Vision() {}

	/** Grabs the current frame (with shaders), shrinks it, returns base64 JPEG. Call from any thread except the render thread. */
	public static String captureJpegBase64(int maxWidth) throws Exception {
		MinecraftClient c = MinecraftClient.getInstance();
		File tmp = File.createTempFile("claude-view", ".png");
		try {
			c.submit(() -> {
				try (NativeImage img = ScreenshotRecorder.takeScreenshot(c.getFramebuffer())) {
					img.writeTo(tmp);
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			}).get(10, TimeUnit.SECONDS);

			BufferedImage src = ImageIO.read(tmp);
			if (src == null) throw new IllegalStateException("Couldn't read the screenshot");
			int w = src.getWidth(), h = src.getHeight();
			if (w > maxWidth) {
				h = Math.max(1, h * maxWidth / w);
				w = maxWidth;
			}
			BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
			Graphics2D g = out.createGraphics();
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.drawImage(src, 0, 0, w, h, null);
			g.dispose();

			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
			ImageWriteParam param = writer.getDefaultWriteParam();
			param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
			param.setCompressionQuality(0.8f);
			try (ImageOutputStream ios = ImageIO.createImageOutputStream(bytes)) {
				writer.setOutput(ios);
				writer.write(null, new IIOImage(out, null, null), param);
			} finally {
				writer.dispose();
			}
			return Base64.getEncoder().encodeToString(bytes.toByteArray());
		} finally {
			Files.deleteIfExists(tmp.toPath());
		}
	}

	/** What the player's crosshair is on, plus which way they face. */
	public static String crosshair() {
		MinecraftClient c = MinecraftClient.getInstance();
		try {
			return c.submit(() -> {
				if (c.player == null || c.world == null) return "unknown";
				String facing = "facing " + c.player.getHorizontalFacing().asString()
						+ (c.player.getPitch() > 45 ? ", looking down" : c.player.getPitch() < -45 ? ", looking up" : "");
				HitResult hit = c.crosshairTarget;
				if (hit instanceof BlockHitResult bh && hit.getType() == HitResult.Type.BLOCK) {
					BlockPos p = bh.getBlockPos();
					String id = Registries.BLOCK.getId(c.world.getBlockState(p).getBlock()).toString().replace("minecraft:", "");
					return String.format(Locale.ROOT, "%s at x=%d y=%d z=%d (%s face), %s", id, p.getX(), p.getY(), p.getZ(), bh.getSide().asString(), facing);
				}
				if (hit instanceof EntityHitResult eh) {
					Entity e = eh.getEntity();
					return e.getName().getString() + " (" + Registries.ENTITY_TYPE.getId(e.getType()).getPath() + "), " + facing;
				}
				return "nothing in reach, " + facing;
			}).get(3, TimeUnit.SECONDS);
		} catch (Exception e) {
			return "unknown";
		}
	}
}
