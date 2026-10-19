package io.github.hsunxl.multiblockbuilder;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.MEStorage;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;

/** Transactional extraction from the ME network behind a bound AE2 interface. */
public final class MeSource implements MaterialSource {
    private final MEStorage storage;
    private final IActionSource actionSource;

    public MeSource(MEStorage storage, IActionSource actionSource) {
        this.storage = storage;
        this.actionSource = actionSource;
    }

    @Override public String id() { return "me"; }

    @Override public Component label() {
        return Component.translatable("multiblock_builder.source.me");
    }

    @Override public Map<Item,Integer> missing(Map<Item,Integer> costs) {
        var missing = new LinkedHashMap<Item,Integer>();
        for (var entry : costs.entrySet()) {
            long available = storage.extract(AEItemKey.of(entry.getKey()),entry.getValue(),Actionable.SIMULATE,actionSource);
            if (available < entry.getValue()) {
                missing.put(entry.getKey(),(int)(entry.getValue() - available));
            }
        }
        return missing;
    }

    @Override public boolean extract(Map<Item,Integer> costs) {
        var taken = new LinkedHashMap<Item,Integer>();
        try {
            for (var entry : costs.entrySet()) {
                long extracted = storage.extract(AEItemKey.of(entry.getKey()),entry.getValue(),Actionable.MODULATE,actionSource);
                if (extracted != entry.getValue()) {
                    if (extracted > 0) storage.insert(AEItemKey.of(entry.getKey()),extracted,Actionable.MODULATE,actionSource);
                    refund(taken);
                    return false;
                }
                taken.put(entry.getKey(),entry.getValue());
            }
            return true;
        } catch (RuntimeException ex) {
            refund(taken);
            throw ex;
        }
    }

    @Override public void refund(Map<Item,Integer> costs) {
        costs.forEach((item,amount) ->
                storage.insert(AEItemKey.of(item),amount,Actionable.MODULATE,actionSource));
    }
}
