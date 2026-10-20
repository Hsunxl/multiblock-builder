package io.github.hsunxl.multiblockbuilder.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
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
 * A tiny CPU isometric renderer for blueprint voxels. The server sends the exposed
 * shell as palette indices, the client textures each cube with that block's own
 * particle sprite, so the thumbnail looks like the real structure.
 */
public final class VoxelPreview {
    private static final float COS30 = 0.8660254F;
    private static final int FULL_BRIGHT = 0xF000F0;

    private record Skin(TextureAtlasSprite sprite, int tint) {}
    private static final Map<String,Skin> SKINS = new HashMap<>();
    private static TextureAtlasSprite fallbackSprite;

    private final int width, height, depth;
    private final int[] slots, tints;
    private final Skin[] skins;
    private final byte[] xs, ys, zs, faces;
    private final float uMin, uMax, vMin, vMax;

    public VoxelPreview(int width, int height, int depth, String[] palette, byte[] data) {
        this.width = width;
        this.height = height;
        this.depth = depth;
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
        var bf = new byte[total];
        var bc = new int[total];
        int count = 0;
        for (int i = 0; i < total; i++) {
            int x = data[i * 4] & 0xFF, y = data[i * 4 + 1] & 0xFF, z = data[i * 4 + 2] & 0xFF;
            int face = 0;
            if (!occupied.get(index(x, y + 1, z))) face |= 1;
            if (!occupied.get(index(x + 1, y, z))) face |= 2;
            if (!occupied.get(index(x, y, z + 1))) face |= 4;
            if (face == 0) continue;
            bx[count] = (byte) x;
            by[count] = (byte) y;
            bz[count] = (byte) z;
            bf[count] = (byte) face;
            bc[count] = data[i * 4 + 3] & 0xFF;
            count++;
        }
        var order = new Integer[count];
        for (int i = 0; i < count; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Integer.compare(
                (bx[a] & 0xFF) + (by[a] & 0xFF) + (bz[a] & 0xFF),
                (bx[b] & 0xFF) + (by[b] & 0xFF) + (bz[b] & 0xFF)));
        xs = new byte[count];
        ys = new byte[count];
        zs = new byte[count];
        faces = new byte[count];
        slots = new int[count];
        for (int i = 0; i < count; i++) {
            int src = order[i];
            xs[i] = bx[src];
            ys[i] = by[src];
            zs[i] = bz[src];
            faces[i] = bf[src];
            slots[i] = bc[src];
        }
        float minU = Float.MAX_VALUE, maxU = -Float.MAX_VALUE, minV = Float.MAX_VALUE, maxV = -Float.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            int x = xs[i] & 0xFF, y = ys[i] & 0xFF, z = zs[i] & 0xFF;
            for (int corner = 0; corner < 8; corner++) {
                float px = x + (corner & 1);
                float py = y + ((corner >> 1) & 1);
                float pz = z + ((corner >> 2) & 1);
                float u = (px - pz) * COS30;
                float v = (px + pz) * 0.5F - py;
                minU = Math.min(minU, u); maxU = Math.max(maxU, u);
                minV = Math.min(minV, v); maxV = Math.max(maxV, v);
            }
        }
        uMin = minU; uMax = maxU; vMin = minV; vMax = maxV;
    }

    private int index(int x, int y, int z) {
        return ((y + 1) * (depth + 2) + (z + 1)) * (width + 2) + (x + 1);
    }

    public int cubes() { return slots.length; }

    /** Draws the structure centred inside the given rectangle, using the current GUI pose. */
    public void render(GuiGraphics graphics, int left, int top, int areaWidth, int areaHeight) {
        if (slots.length == 0) return;
        float spanU = Math.max(0.001F, uMax - uMin);
        float spanV = Math.max(0.001F, vMax - vMin);
        float scale = Math.min((areaWidth - 14F) / spanU, (areaHeight - 14F) / spanV);
        float offsetX = left + areaWidth / 2F - (uMin + uMax) / 2F * scale;
        float offsetY = top + areaHeight / 2F - (vMin + vMax) / 2F * scale;

        Matrix4f matrix = graphics.pose().last().pose();
        VertexConsumer buffer = graphics.bufferSource().getBuffer(RenderType.text(TextureAtlas.LOCATION_BLOCKS));
        for (int i = 0; i < slots.length; i++) {
            int slot = slots[i];
            Skin skin = skins[slot];
            if (skin == null) continue;
            var sprite = skin.sprite();
            float u0 = sprite.getU0(), u1 = sprite.getU1(), v0 = sprite.getV0(), v1 = sprite.getV1();
            int x = xs[i] & 0xFF, y = ys[i] & 0xFF, z = zs[i] & 0xFF;
            int face = faces[i] & 0xFF;
            int tint = tints[slot];
            if ((face & 1) != 0) {
                quad(buffer, matrix, shade(tint, 1.0F), u0, v0, u1, v1,
                        sx(x, z, scale, offsetX), sy(x, y + 1, z, scale, offsetY),
                        sx(x, z + 1, scale, offsetX), sy(x, y + 1, z + 1, scale, offsetY),
                        sx(x + 1, z + 1, scale, offsetX), sy(x + 1, y + 1, z + 1, scale, offsetY),
                        sx(x + 1, z, scale, offsetX), sy(x + 1, y + 1, z, scale, offsetY));
            }
            if ((face & 2) != 0) {
                quad(buffer, matrix, shade(tint, 0.80F), u0, v1, u1, v0,
                        sx(x + 1, z, scale, offsetX), sy(x + 1, y, z, scale, offsetY),
                        sx(x + 1, z, scale, offsetX), sy(x + 1, y + 1, z, scale, offsetY),
                        sx(x + 1, z + 1, scale, offsetX), sy(x + 1, y + 1, z + 1, scale, offsetY),
                        sx(x + 1, z + 1, scale, offsetX), sy(x + 1, y, z + 1, scale, offsetY));
            }
            if ((face & 4) != 0) {
                quad(buffer, matrix, shade(tint, 0.64F), u0, v1, u1, v0,
                        sx(x, z + 1, scale, offsetX), sy(x, y, z + 1, scale, offsetY),
                        sx(x + 1, z + 1, scale, offsetX), sy(x + 1, y, z + 1, scale, offsetY),
                        sx(x + 1, z + 1, scale, offsetX), sy(x + 1, y + 1, z + 1, scale, offsetY),
                        sx(x, z + 1, scale, offsetX), sy(x, y + 1, z + 1, scale, offsetY));
            }
        }
        graphics.flush();
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

    private static float sx(float px, float pz, float scale, float offset) {
        return ((px - pz) * COS30) * scale + offset;
    }

    private static float sy(float px, float py, float pz, float scale, float offset) {
        return ((px + pz) * 0.5F - py) * scale + offset;
    }

    /** Vertices must be screen-clockwise to survive the GUI back-face culling. */
    private static void quad(VertexConsumer buffer, Matrix4f matrix, int rgb,
                             float u0, float v0, float u1, float v1,
                             float x1, float y1, float x2, float y2,
                             float x3, float y3, float x4, float y4) {
        vertex(buffer, matrix, x1, y1, rgb, u0, v0);
        vertex(buffer, matrix, x2, y2, rgb, u0, v1);
        vertex(buffer, matrix, x3, y3, rgb, u1, v1);
        vertex(buffer, matrix, x4, y4, rgb, u1, v0);
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
