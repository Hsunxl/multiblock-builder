package io.github.hsunxl.multiblockbuilder.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.joml.Matrix4f;

/**
 * A tiny CPU voxel renderer for blueprint thumbnails: textured cubes, perspective
 * turntable rotation and painter's-order depth sorting, so a 5,832-block matrix is
 * still cheap to draw every frame.
 */
public final class VoxelPreview {
    private static final float PITCH = 0.422F;
    private static final float COS_P = (float) Math.cos(PITCH);
    private static final float SIN_P = (float) Math.sin(PITCH);
    private static final float SPIN = 0.0002618F; // 15 degrees per second
    private static final int FULL_BRIGHT = 0xF000F0;

    /** +Y, -Y, +X, -X, +Z, -Z: four corners each, counter-clockwise seen from outside. */
    private static final int[][] FACES = {
            {0, 1, 0, 0, 1, 1, 1, 1, 1, 1, 1, 0},
            {0, 0, 0, 1, 0, 0, 1, 0, 1, 0, 0, 1},
            {1, 0, 0, 1, 1, 0, 1, 1, 1, 1, 0, 1},
            {0, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1, 0},
            {0, 0, 1, 1, 0, 1, 1, 1, 1, 0, 1, 1},
            {0, 0, 0, 0, 1, 0, 1, 1, 0, 1, 0, 0}
    };
    private static final float[] SHADE = {1.0F, 0.45F, 0.82F, 0.72F, 0.66F, 0.56F};

    private record Skin(TextureAtlasSprite sprite, int tint) {}
    private static final Map<String,Skin> SKINS = new HashMap<>();
    private static TextureAtlasSprite fallbackSprite;

    private final int width, height, depth;
    private final int[] slots, tints;
    private final Skin[] skins;
    private final byte[] xs, ys, zs, mask;
    private final float[] depths;
    private final Integer[] order;
    private final Comparator<Integer> byDepth;
    private final float centerX, centerY, centerZ, radius, phase;

    private float cosY, sinY, focal, cameraDistance, screenX, screenY, outX, outY;

    public VoxelPreview(String id,int width,int height,int depth,String[] palette,byte[] data) {
        this.width = width;
        this.height = height;
        this.depth = depth;
        this.centerX = width / 2F;
        this.centerY = height / 2F;
        this.centerZ = depth / 2F;
        this.radius = 0.5F * (float) Math.sqrt(width * width + height * height + depth * depth);
        this.phase = (id == null ? 0 : (id.hashCode() & 0xFF) / 255F) * (float) (Math.PI * 2);

        skins = new Skin[palette.length];
        tints = new int[palette.length];
        for (int i = 0; i < palette.length; i++) {
            var skin = SKINS.computeIfAbsent(palette[i], VoxelPreview::resolve);
            if (SKINS.size() > 512) SKINS.clear();
            skins[i] = skin;
            tints[i] = skin.tint();
        }

        int total = data.length / 4;
        var occupied = new java.util.BitSet((width + 2) * (height + 2) * (depth + 2));
        for (int i = 0; i < total; i++) {
            occupied.set(index(data[i * 4] & 0xFF, data[i * 4 + 1] & 0xFF, data[i * 4 + 2] & 0xFF));
        }
        var bx = new byte[total];
        var by = new byte[total];
        var bz = new byte[total];
        var bm = new byte[total];
        var bs = new int[total];
        int count = 0;
        for (int i = 0; i < total; i++) {
            int x = data[i * 4] & 0xFF, y = data[i * 4 + 1] & 0xFF, z = data[i * 4 + 2] & 0xFF;
            int faces = 0;
            if (!occupied.get(index(x, y + 1, z))) faces |= 1;
            if (!occupied.get(index(x, y - 1, z))) faces |= 2;
            if (!occupied.get(index(x + 1, y, z))) faces |= 4;
            if (!occupied.get(index(x - 1, y, z))) faces |= 8;
            if (!occupied.get(index(x, y, z + 1))) faces |= 16;
            if (!occupied.get(index(x, y, z - 1))) faces |= 32;
            if (faces == 0) continue;
            bx[count] = (byte) x;
            by[count] = (byte) y;
            bz[count] = (byte) z;
            bm[count] = (byte) faces;
            bs[count] = data[i * 4 + 3] & 0xFF;
            count++;
        }
        xs = Arrays.copyOf(bx, count);
        ys = Arrays.copyOf(by, count);
        zs = Arrays.copyOf(bz, count);
        mask = Arrays.copyOf(bm, count);
        slots = Arrays.copyOf(bs, count);
        depths = new float[count];
        order = new Integer[count];
        for (int i = 0; i < count; i++) order[i] = i;
        byDepth = (a, b) -> Float.compare(depths[a], depths[b]);
    }

    private int index(int x, int y, int z) {
        return ((y + 1) * (depth + 2) + (z + 1)) * (width + 2) + (x + 1);
    }

    public int cubes() { return slots.length; }

    /** Draws the structure turntable-style inside the given rectangle. */
    public void render(GuiGraphics graphics, int left, int top, int areaWidth, int areaHeight) {
        if (slots.length == 0) return;
        float yaw = phase + Util.getMillis() * SPIN;
        cosY = (float) Math.cos(yaw);
        sinY = (float) Math.sin(yaw);
        screenX = left + areaWidth / 2F;
        screenY = top + areaHeight / 2F;

        float spanH = (float) Math.sqrt((float) width * width + (float) depth * depth);
        float spanV = height * COS_P + spanH * SIN_P;
        cameraDistance = radius * 3.0F + 6.0F;
        focal = Math.min((areaWidth - 10F) * cameraDistance / spanH,
                (areaHeight - 10F) * cameraDistance / spanV) * 0.9F;

        for (int i = 0; i < slots.length; i++) {
            float px = (xs[i] & 0xFF) + 0.5F - centerX;
            float py = (ys[i] & 0xFF) + 0.5F - centerY;
            float pz = (zs[i] & 0xFF) + 0.5F - centerZ;
            float zr = px * sinY + pz * cosY;
            depths[i] = py * SIN_P + zr * COS_P;
        }
        Arrays.sort(order, byDepth);

        Matrix4f matrix = graphics.pose().last().pose();
        VertexConsumer buffer = graphics.bufferSource().getBuffer(RenderType.text(TextureAtlas.LOCATION_BLOCKS));
        for (int idx = 0; idx < order.length; idx++) {
            int i = order[idx];
            int slot = slots[i];
            Skin skin = skins[slot];
            if (skin == null) continue;
            var sprite = skin.sprite();
            float u0 = sprite.getU0(), u1 = sprite.getU1(), v0 = sprite.getV0(), v1 = sprite.getV1();
            int x = xs[i] & 0xFF, y = ys[i] & 0xFF, z = zs[i] & 0xFF;
            int faces = mask[i] & 0xFF;
            for (int f = 0; f < 6; f++) {
                if ((faces & (1 << f)) == 0) continue;
                int[] face = FACES[f];
                project(x + face[0], y + face[1], z + face[2]);
                float x1 = outX, y1 = outY;
                project(x + face[3], y + face[4], z + face[5]);
                float x2 = outX, y2 = outY;
                project(x + face[6], y + face[7], z + face[8]);
                float x3 = outX, y3 = outY;
                project(x + face[9], y + face[10], z + face[11]);
                float x4 = outX, y4 = outY;
                int rgb = shade(tints[slot], SHADE[f]);
                vertex(buffer, matrix, x1, y1, rgb, u0, v0);
                vertex(buffer, matrix, x2, y2, rgb, u0, v1);
                vertex(buffer, matrix, x3, y3, rgb, u1, v1);
                vertex(buffer, matrix, x4, y4, rgb, u1, v0);
            }
        }
        graphics.flush();
    }

    private void project(float x, float y, float z) {
        float px = x - centerX, py = y - centerY, pz = z - centerZ;
        float xr = px * cosY - pz * sinY;
        float zr = px * sinY + pz * cosY;
        float yr = py * COS_P - zr * SIN_P;
        float dz = cameraDistance - (py * SIN_P + zr * COS_P);
        outX = screenX + xr * focal / dz;
        outY = screenY - yr * focal / dz;
    }

    private static Skin resolve(String id) {
        var mc = Minecraft.getInstance();
        var key = ResourceLocation.tryParse(id);
        if (key != null && BuiltInRegistries.BLOCK.containsKey(key)) {
            var block = BuiltInRegistries.BLOCK.get(key);
            try {
                var model = mc.getBlockRenderer().getBlockModel(block.defaultBlockState());
                var sprite = model.getParticleIcon(ModelData.EMPTY);
                if (sprite != null && !sprite.contents().name().getPath().equals("missingno")) {
                    return new Skin(sprite, 0xFFFFFF);
                }
            } catch (RuntimeException ignored) {
                // fall through to the tinted fallback
            }
            int color = block.defaultMapColor().col;
            if (color == 0) color = 0x9AA7B2;
            return new Skin(fallback(), color);
        }
        return new Skin(fallback(), 0x9AA7B2);
    }

    private static synchronized TextureAtlasSprite fallback() {
        if (fallbackSprite == null) {
            fallbackSprite = Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS)
                    .apply(ResourceLocation.withDefaultNamespace("block/quartz_block_side"));
        }
        return fallbackSprite;
    }

    private static void vertex(VertexConsumer buffer, Matrix4f matrix, float x, float y, int rgb, float u, float v) {
        int r = rgb >> 16 & 0xFF, g = rgb >> 8 & 0xFF, b = rgb & 0xFF;
        buffer.addVertex(matrix, x, y, 0).setColor(r, g, b, 255).setUv(u, v).setLight(FULL_BRIGHT);
    }

    private static int shade(int rgb, float factor) {
        int r = Math.min(255, Math.round((rgb >> 16 & 0xFF) * factor));
        int g = Math.min(255, Math.round((rgb >> 8 & 0xFF) * factor));
        int b = Math.min(255, Math.round((rgb & 0xFF) * factor));
        return r << 16 | g << 8 | b;
    }
}
