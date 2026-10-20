package io.github.hsunxl.multiblockbuilder.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.Arrays;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;

/**
 * A tiny CPU isometric renderer for blueprint voxels. The server sends the exposed
 * shell as palette indices, so a 5,832-block matrix still costs only a few thousand quads.
 */
public final class VoxelPreview {
    private static final float COS30 = 0.8660254F;

    private final int width, height, depth;
    private final int[] colors;
    private final byte[] xs, ys, zs, faces;
    private final float uMin, uMax, vMin, vMax;

    public VoxelPreview(int width, int height, int depth, int[] palette, byte[] data) {
        this.width = width;
        this.height = height;
        this.depth = depth;
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
            int slot = data[i * 4 + 3] & 0xFF;
            bc[count] = slot < palette.length ? palette[slot] : 0x9AA7B2;
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
        colors = new int[count];
        for (int i = 0; i < count; i++) {
            int src = order[i];
            xs[i] = bx[src];
            ys[i] = by[src];
            zs[i] = bz[src];
            faces[i] = bf[src];
            colors[i] = bc[src];
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

    public int cubes() { return colors.length; }

    /** Draws the structure centred inside the given rectangle, using the current GUI pose. */
    public void render(GuiGraphics graphics, int left, int top, int areaWidth, int areaHeight) {
        if (colors.length == 0) return;
        float spanU = Math.max(0.001F, uMax - uMin);
        float spanV = Math.max(0.001F, vMax - vMin);
        float scale = Math.min((areaWidth - 14F) / spanU, (areaHeight - 14F) / spanV);
        float offsetX = left + areaWidth / 2F - (uMin + uMax) / 2F * scale;
        float offsetY = top + areaHeight / 2F - (vMin + vMax) / 2F * scale;

        Matrix4f matrix = graphics.pose().last().pose();
        VertexConsumer buffer = graphics.bufferSource().getBuffer(RenderType.gui());
        for (int i = 0; i < colors.length; i++) {
            int x = xs[i] & 0xFF, y = ys[i] & 0xFF, z = zs[i] & 0xFF;
            int face = faces[i] & 0xFF;
            int color = colors[i];
            if ((face & 1) != 0) {
                quad(buffer, matrix,
                        sx(x, z, scale, offsetX), sy(x, y + 1, z, scale, offsetY),
                        sx(x, z + 1, scale, offsetX), sy(x, y + 1, z + 1, scale, offsetY),
                        sx(x + 1, z + 1, scale, offsetX), sy(x + 1, y + 1, z + 1, scale, offsetY),
                        sx(x + 1, z, scale, offsetX), sy(x + 1, y + 1, z, scale, offsetY),
                        shade(color, 1.0F));
            }
            if ((face & 2) != 0) {
                quad(buffer, matrix,
                        sx(x + 1, z, scale, offsetX), sy(x + 1, y, z, scale, offsetY),
                        sx(x + 1, z, scale, offsetX), sy(x + 1, y + 1, z, scale, offsetY),
                        sx(x + 1, z + 1, scale, offsetX), sy(x + 1, y + 1, z + 1, scale, offsetY),
                        sx(x + 1, z + 1, scale, offsetX), sy(x + 1, y, z + 1, scale, offsetY),
                        shade(color, 0.72F));
            }
            if ((face & 4) != 0) {
                quad(buffer, matrix,
                        sx(x, z + 1, scale, offsetX), sy(x, y, z + 1, scale, offsetY),
                        sx(x + 1, z + 1, scale, offsetX), sy(x + 1, y, z + 1, scale, offsetY),
                        sx(x + 1, z + 1, scale, offsetX), sy(x + 1, y + 1, z + 1, scale, offsetY),
                        sx(x, z + 1, scale, offsetX), sy(x, y + 1, z + 1, scale, offsetY),
                        shade(color, 0.55F));
            }
        }
    }

    private static float sx(float px, float pz, float scale, float offset) {
        return ((px - pz) * COS30) * scale + offset;
    }

    private static float sy(float px, float py, float pz, float scale, float offset) {
        return ((px + pz) * 0.5F - py) * scale + offset;
    }

    private static void quad(VertexConsumer buffer, Matrix4f matrix,
                             float x1, float y1, float x2, float y2, float x3, float y3, float x4, float y4,
                             int rgb) {
        int r = rgb >> 16 & 0xFF, g = rgb >> 8 & 0xFF, b = rgb & 0xFF;
        buffer.addVertex(matrix, x1, y1, 0).setColor(r, g, b, 255);
        buffer.addVertex(matrix, x2, y2, 0).setColor(r, g, b, 255);
        buffer.addVertex(matrix, x3, y3, 0).setColor(r, g, b, 255);
        buffer.addVertex(matrix, x4, y4, 0).setColor(r, g, b, 255);
    }

    private static int shade(int rgb, float factor) {
        int r = Math.min(255, Math.round((rgb >> 16 & 0xFF) * factor));
        int g = Math.min(255, Math.round((rgb >> 8 & 0xFF) * factor));
        int b = Math.min(255, Math.round((rgb & 0xFF) * factor));
        return r << 16 | g << 8 | b;
    }
}
