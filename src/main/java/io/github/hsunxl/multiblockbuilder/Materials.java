package io.github.hsunxl.multiblockbuilder;

import appeng.blockentity.misc.InterfaceBlockEntity;
import appeng.me.helpers.PlayerSource;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** Chooses ME when an interface is bound, otherwise the existing inventory source. */
public final class Materials {
    private Materials() {}

    public static MaterialSource resolve(ServerPlayer player, ItemStack tool) {
        GlobalPos bound = Selection.boundInterface(Selection.read(tool));
        if (bound == null) return new InventorySource(player);

        var level = player.getServer().getLevel(bound.dimension());
        if (level == null || !level.hasChunkAt(bound.pos())
                || !(level.getBlockEntity(bound.pos()) instanceof InterfaceBlockEntity iface)) {
            player.sendSystemMessage(Component.translatable("multiblock_builder.interface_missing",bound.pos().toShortString()));
            return null;
        }
        var grid = iface.getMainNode().getGrid();
        if (grid == null) {
            player.sendSystemMessage(Component.translatable("multiblock_builder.interface_offline",bound.pos().toShortString()));
            return null;
        }
        return new MeSource(grid.getStorageService().getInventory(),new PlayerSource(player));
    }
}
