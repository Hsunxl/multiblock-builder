package io.github.hsunxl.multiblockbuilder;

/** Minecraft-independent geometry shared by placement and preview. */
public final class Geometry {
    private Geometry() {}
    public record Point(int x, int y, int z) {}
    public static Point rotate(int x, int y, int z, int width, int depth, int turns) {
        return switch (Math.floorMod(turns, 4)) {
            case 1 -> new Point(depth - 1 - z, y, x);
            case 2 -> new Point(width - 1 - x, y, depth - 1 - z);
            case 3 -> new Point(z, y, width - 1 - x);
            default -> new Point(x, y, z);
        };
    }
}
