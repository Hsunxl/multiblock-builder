package io.github.hsunxl.multiblockbuilder;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.EventHooks;

/** A bounded synchronous placement. Rechecks every target; never loads chunks or overwrites blocks. */
public final class Placement {
    private Placement() {}
    public record Plan(List<Blueprint.Cell> missing, Map<Item,Integer> costs) {}
    public static Plan plan(ServerPlayer p, Blueprint b, BlockPos origin, int turns) {
        var level = p.serverLevel();
        if (p.isSpectator() || !p.mayBuild()) throw new IllegalArgumentException("Building is not allowed in this game mode");
        if (p.blockPosition().distSqr(origin) > 32*32) throw new IllegalArgumentException("Anchor must be within 32 blocks");
        if (!p.getAbilities().instabuild && !b.survival()) throw new IllegalArgumentException("This blueprint is creative-only; its material mapping has not been approved");
        var missing = new ArrayList<Blueprint.Cell>();
        var costs = new LinkedHashMap<Item,Integer>();
        for (var cell : b.rotated(turns)) {
            var pos = origin.offset(cell.offset());
            if (level.isOutsideBuildHeight(pos) || !level.getWorldBorder().isWithinBounds(pos) || !level.hasChunkAt(pos))
                throw new IllegalArgumentException("Unloaded or out-of-bounds target: " + pos.toShortString());
            var current = level.getBlockState(pos);
            if (current.equals(cell.state())) continue;
            if (!current.isAir() || level.getBlockEntity(pos)!=null) throw new IllegalArgumentException("Blocked: " + pos.toShortString());
            if (!level.mayInteract(p,pos) || !p.mayUseItemAt(pos,Direction.UP,p.getMainHandItem()))
                throw new IllegalArgumentException("Protected: " + pos.toShortString());
            missing.add(new Blueprint.Cell(pos,cell.state()));
            costs.merge(cell.state().getBlock().asItem(),1,Integer::sum);
        }
        return new Plan(List.copyOf(missing),costs);
    }

    private static void reportMissing(ServerPlayer p, MaterialSource source, Map<Item,Integer> missing) {
        p.sendSystemMessage(source.missingHeader());
        missing.forEach((item,amount) ->
                p.sendSystemMessage(Component.translatable("multiblock_builder.missing",item.getDescription(),amount)));
    }

    public static boolean build(ServerPlayer p, ItemStack tool, Blueprint b, BlockPos origin, int turns) {
        var snapshots = new ArrayList<BlockSnapshot>();
        var level = p.serverLevel();
        MaterialSource source = null;
        Map<Item,Integer> charged = Map.of();
        try {
            Plan plan = plan(p,b,origin,turns);
            if (!p.getAbilities().instabuild) {
                source = Materials.resolve(p,tool);
                if (source == null) return false;
                var missing = source.missing(plan.costs());
                if (!missing.isEmpty()) {
                    reportMissing(p,source,missing);
                    return false;
                }
            }
            // Neighbor updates are deferred until the whole layout exists. Post-place protection
            // events see the tentative state and can veto it before any inventory/ME charge.
            for (var cell : plan.missing()) {
                if (!level.getBlockState(cell.offset()).isAir() || level.getBlockEntity(cell.offset())!=null)
                    throw new IllegalArgumentException("Target changed during placement: " + cell.offset().toShortString());
                var snapshot = BlockSnapshot.create(level.dimension(),level,cell.offset());
                snapshots.add(snapshot);
                if (!level.setBlock(cell.offset(),cell.state(),Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE))
                    throw new IllegalArgumentException("Placement failed: " + cell.offset().toShortString());
            }
            for (int i=0;i<plan.missing().size();i++) {
                var cell=plan.missing().get(i);
                if (!level.getBlockState(cell.offset()).equals(cell.state()) || !cell.state().canSurvive(level,cell.offset()))
                    throw new IllegalArgumentException("Unsupported or changed block: " + cell.offset().toShortString());
                if (EventHooks.onBlockPlace(p,snapshots.get(i),Direction.UP)) throw new IllegalArgumentException("Placement cancelled by protection/event handler");
            }
            for (var cell:plan.missing()) if (!level.getBlockState(cell.offset()).equals(cell.state()))
                throw new IllegalArgumentException("Layout changed during protection checks");
            if (!p.getAbilities().instabuild) {
                if (!source.extract(plan.costs())) throw new IllegalStateException("Materials changed during build; nothing was charged");
                charged = plan.costs();
            }
            // Commit point: material charge and layout now agree. Generic mod callbacks during
            // neighbor notifications cannot be made transactional; do not claim otherwise.
            snapshots.clear();
            Selection.clearOrigin(tool);
            for (var cell:plan.missing()) {
                try { level.updateNeighborsAt(cell.offset(),cell.state().getBlock()); }
                catch (RuntimeException ex) { BuilderMod.LOG.error("Post-commit neighbor callback failed at {}",cell.offset(),ex); }
            }
            boolean formed = Formation.activate(level,b,origin,turns,p);
            p.sendSystemMessage(Component.translatable(formed?"multiblock_builder.built_formed":"multiblock_builder.built",plan.missing().size()));
            return true;
        } catch (RuntimeException ex) {
            if (!charged.isEmpty() && source != null) {
                try { source.refund(charged); }
                catch (RuntimeException refundFailure) { BuilderMod.LOG.error("Material refund failed",refundFailure); }
            }
            for (int i=snapshots.size()-1;i>=0;i--) {
                try {
                    var snapshot=snapshots.get(i);
                    snapshot.restore(Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                    if (!level.getBlockState(snapshot.getPos()).equals(snapshot.getState()))
                        BuilderMod.LOG.error("Rollback incomplete at {}",snapshot.getPos());
                } catch(RuntimeException failure) { BuilderMod.LOG.error("Rollback callback failed",failure); }
            }
            p.sendSystemMessage(Component.literal(ex.getMessage()==null?ex.getClass().getSimpleName():ex.getMessage()));
            BuilderMod.LOG.debug("Build aborted",ex);
            return false;
        }
    }
}
