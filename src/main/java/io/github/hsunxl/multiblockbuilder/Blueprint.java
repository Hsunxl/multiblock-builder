package io.github.hsunxl.multiblockbuilder;

import com.google.gson.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.item.BlockItem;

/** Server-owned, data-only blueprint. No block entity NBT, commands or entities. */
public record Blueprint(ResourceLocation id, String title, int width, int height, int depth,
                        boolean survival, String formation, List<Cell> cells, String json) {
    public static final int MAX_CELLS = 8192;
    public record Cell(BlockPos offset, BlockState state) {}
    public Blueprint { cells = List.copyOf(cells); }
    public List<Cell> rotated(int turns) {
        Rotation rotation = Rotation.values()[Math.floorMod(turns, 4)];
        return cells.stream().map(c -> {
            var p = Geometry.rotate(c.offset.getX(), c.offset.getY(), c.offset.getZ(), width, depth, turns);
            return new Cell(new BlockPos(p.x(), p.y(), p.z()), c.state.rotate(rotation));
        }).toList();
    }
    public static Blueprint parse(ResourceLocation id, JsonObject root) {
        var size = root.getAsJsonArray("size");
        if (size == null || size.size() != 3) throw new IllegalArgumentException("size must be [x,y,z]");
        int w = size.get(0).getAsInt(), h = size.get(1).getAsInt(), d = size.get(2).getAsInt();
        if (w < 1 || h < 1 || d < 1 || w > 64 || h > 64 || d > 64) throw new IllegalArgumentException("size range: 1..64");
        var blocks = root.getAsJsonArray("blocks");
        if (blocks == null || blocks.isEmpty() || blocks.size() > MAX_CELLS) throw new IllegalArgumentException("blocks range: 1..4096");
        boolean survival = root.has("survival") && root.get("survival").getAsBoolean();
        String formation = root.has("formation") ? root.get("formation").getAsString() : "";
        if (!formation.isEmpty() && (formation.length() > 120 || !formation.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")))
            throw new IllegalArgumentException("Invalid formation adapter id");
        var cells = new ArrayList<Cell>();
        var seen = new HashSet<BlockPos>();
        for (var element : blocks) {
            var b = element.getAsJsonObject();
            if (b.has("nbt")) throw new IllegalArgumentException("Block entity NBT is not supported");
            var pos = b.getAsJsonArray("pos");
            if (pos == null || pos.size() != 3) throw new IllegalArgumentException("Invalid pos");
            var p = new BlockPos(pos.get(0).getAsInt(), pos.get(1).getAsInt(), pos.get(2).getAsInt());
            if (p.getX()<0 || p.getY()<0 || p.getZ()<0 || p.getX()>=w || p.getY()>=h || p.getZ()>=d || !seen.add(p))
                throw new IllegalArgumentException("Duplicate or out-of-bounds position " + p);
            var blockId = ResourceLocation.parse(b.get("block").getAsString());
            if (!BuiltInRegistries.BLOCK.containsKey(blockId)) throw new IllegalArgumentException("Missing block " + blockId);
            BlockState state = BuiltInRegistries.BLOCK.get(blockId).defaultBlockState();
            if (b.has("properties")) for (var entry : b.getAsJsonObject("properties").entrySet()) {
                var prop = state.getBlock().getStateDefinition().getProperty(entry.getKey());
                if (prop == null) throw new IllegalArgumentException("Unknown property " + entry.getKey());
                state = with(state, prop, entry.getValue().getAsString());
            }
            if (state.isAir()) throw new IllegalArgumentException("Omit ignored air; air cells are not building materials");
            if (survival && !survivalSafe(state)) throw new IllegalArgumentException("Requires creative-only blueprint: " + blockId);
            cells.add(new Cell(p, state));
        }
        cells.sort(Comparator.comparingInt((Cell c) -> c.offset.getY()).thenComparingInt(c -> c.offset.getZ()).thenComparingInt(c -> c.offset.getX()));
        String title = root.has("title") ? root.get("title").getAsString() : id.toString();
        if (title.length() > 160) throw new IllegalArgumentException("Title too long");
        String json = root.toString();
        if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1_500_000) throw new IllegalArgumentException("Blueprint too large");
        return new Blueprint(id,title,w,h,d,survival,formation,cells,json);
    }
    private static <T extends Comparable<T>> BlockState with(BlockState s, Property<T> p, String value) {
        return s.setValue(p,p.getValue(value).orElseThrow(() -> new IllegalArgumentException("Invalid " + p.getName() + "=" + value)));
    }
    public static boolean survivalSafe(BlockState s) {
        // Do not guess multi-cell item costs, water/bucket costs, or initialized BE contents.
        if (!(s.getBlock().asItem() instanceof BlockItem item) || item.getBlock() != s.getBlock()) return false;
        if (!s.getFluidState().isEmpty() || s.getBlock() instanceof DoorBlock || s.getBlock() instanceof BedBlock
                || s.getBlock() instanceof DoublePlantBlock) return false;
        for (var entry : s.getValues().entrySet()) {
            String key = entry.getKey().getName(), value = entry.getValue().toString();
            if (key.equals("type") && value.equals("double")) return false;
            if (Set.of("eggs","pickles","candles","layers","honey_level","level","berries","age").contains(key)
                    && !entry.getValue().equals(s.getBlock().defaultBlockState().getValue(entry.getKey()))) return false;
        }
        return true;
    }
}
