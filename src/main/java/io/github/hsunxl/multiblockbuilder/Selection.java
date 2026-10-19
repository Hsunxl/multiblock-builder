package io.github.hsunxl.multiblockbuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/** All mutations happen on the server's held stack, not from client-provided NBT. */
public final class Selection {
    private Selection() {}
    public static CompoundTag read(ItemStack stack) { return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag(); }
    public static void write(ItemStack stack, CompoundTag tag) { stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag)); }
    public static void clearOrigin(ItemStack stack) { var t=read(stack); t.remove("origin"); t.remove("dimension"); write(stack,t); }
    public static BlockPos origin(CompoundTag t) { return t.contains("origin") ? BlockPos.of(t.getLong("origin")) : null; }
}
