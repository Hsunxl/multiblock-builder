package io.github.hsunxl.multiblockbuilder;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Existing behaviour: plain items in the player's main inventory. */
public final class InventorySource implements MaterialSource {
    private final ServerPlayer player;

    public InventorySource(ServerPlayer player) {
        this.player = player;
    }

    @Override public String id() { return "inventory"; }

    @Override public Component label() {
        return Component.translatable("multiblock_builder.source.inventory");
    }

    private static int count(ServerPlayer player, Item item) {
        return player.getInventory().items.stream()
                .filter(stack -> stack.is(item) && stack.getComponentsPatch().isEmpty())
                .mapToInt(ItemStack::getCount).sum();
    }

    @Override public Map<Item,Integer> missing(Map<Item,Integer> costs) {
        var missing = new LinkedHashMap<Item,Integer>();
        costs.forEach((item,amount) -> {
            int shortfall = amount - count(player,item);
            if (shortfall > 0) missing.put(item,shortfall);
        });
        return missing;
    }

    @Override public boolean extract(Map<Item,Integer> costs) {
        if (!missing(costs).isEmpty()) return false;
        var taken = new LinkedHashMap<Item,Integer>();
        for (var entry : costs.entrySet()) {
            int remaining = entry.getValue();
            int original = remaining;
            int takenForItem = 0;
            for (var stack : player.getInventory().items) {
                if (remaining == 0) break;
                if (!stack.is(entry.getKey()) || !stack.getComponentsPatch().isEmpty()) continue;
                int take = Math.min(remaining,stack.getCount());
                stack.shrink(take);
                remaining -= take;
                takenForItem += take;
            }
            if (remaining != 0) {
                if (takenForItem > 0) refund(Map.of(entry.getKey(),takenForItem));
                refund(taken);
                return false;
            }
            taken.put(entry.getKey(),original);
        }
        player.getInventory().setChanged();
        return true;
    }

    @Override public void refund(Map<Item,Integer> costs) {
        costs.forEach((item,amount) -> {
            int remaining = amount;
            while (remaining > 0) {
                int size = Math.min(remaining,new ItemStack(item).getMaxStackSize());
                var stack = new ItemStack(item,size);
                if (!player.getInventory().add(stack)) player.drop(stack,false);
                remaining -= size;
            }
        });
        player.getInventory().setChanged();
    }
}
