package io.github.hsunxl.multiblockbuilder;

import java.util.Map;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;

/** Server-side material source for one build; implementations must be transaction-safe. */
public interface MaterialSource {
    String id();

    Component label();

    /** Returns only the missing amount for each item, preserving iteration order. */
    Map<Item,Integer> missing(Map<Item,Integer> costs);

    /** Takes all costs or none. Returns false after restoring anything partially taken. */
    boolean extract(Map<Item,Integer> costs);

    /** Returns previously taken costs after a later step failed. */
    void refund(Map<Item,Integer> costs);

    default Component missingHeader() {
        return Component.translatable("multiblock_builder.missing_header", label());
    }
}
