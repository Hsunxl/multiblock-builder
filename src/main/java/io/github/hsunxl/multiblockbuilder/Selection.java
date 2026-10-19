package io.github.hsunxl.multiblockbuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;

/** All mutations happen on the server's held stack, not from client-provided NBT. */
public final class Selection {
    private Selection() {}
    public static CompoundTag read(ItemStack stack) { return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag(); }
    public static void write(ItemStack stack, CompoundTag tag) { stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag)); }
    public static void clearOrigin(ItemStack stack) { var t=read(stack); t.remove("origin"); t.remove("dimension"); write(stack,t); }
    public static BlockPos origin(CompoundTag t) { return t.contains("origin") ? BlockPos.of(t.getLong("origin")) : null; }

    public static void bindInterface(ItemStack stack, ResourceKey<Level> dimension, BlockPos pos) {
        var t=read(stack);
        t.putString("me_dimension",dimension.location().toString());
        t.putLong("me_pos",pos.asLong());
        write(stack,t);
    }
    public static void clearInterface(ItemStack stack) {
        var t=read(stack); t.remove("me_dimension"); t.remove("me_pos"); write(stack,t);
    }
    public static GlobalPos boundInterface(CompoundTag t) {
        if (!t.contains("me_dimension",Tag.TAG_STRING) || !t.contains("me_pos",Tag.TAG_LONG)) return null;
        try {
            var dimension=ResourceKey.create(Registries.DIMENSION,ResourceLocation.parse(t.getString("me_dimension")));
            return GlobalPos.of(dimension,BlockPos.of(t.getLong("me_pos")));
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
