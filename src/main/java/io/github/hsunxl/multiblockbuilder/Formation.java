package io.github.hsunxl.multiblockbuilder;

import java.lang.reflect.Method;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;

/** Optional, data-driven activation of a multiblock's own assembly API. */
public final class Formation {
    public static final String ULTIMATE_SUPER_ASSEMBLER_MATRIX = "extendedae_plus:ultimate_super_assembler_matrix";

    private Formation() {}

    /**
     * Called after every block and neighbour update is in place. Returns true only when a
     * mod-specific assembly API confirmed the structure has formed.
     */
    public static boolean activate(ServerLevel level, Blueprint blueprint, BlockPos origin, int turns, ServerPlayer player) {
        if (blueprint.formation().isEmpty()) return false;
        if ((turns & 3) != 0) {
            player.sendSystemMessage(Component.translatable("multiblock_builder.formation_rotated"));
            return false;
        }
        if (!blueprint.formation().equals(ULTIMATE_SUPER_ASSEMBLER_MATRIX)) {
            player.sendSystemMessage(Component.translatable("multiblock_builder.formation_unknown",blueprint.formation()));
            return false;
        }
        if (!ModList.get().isLoaded("extendedae_plus")) {
            player.sendSystemMessage(Component.translatable("multiblock_builder.formation_missing"));
            return false;
        }
        try {
            Class<?> calculator = Class.forName("com.extendedae_plus.content.matrix.supermatrix.SuperAssemblerMatrixCalculator");
            Method formUltimate = calculator.getMethod("formUltimate",ServerLevel.class,BlockPos.class);
            Object result = formUltimate.invoke(null,level,origin);
            if (Boolean.TRUE.equals(result)) return true;
            player.sendSystemMessage(Component.translatable("multiblock_builder.formation_failed"));
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ex) {
            BuilderMod.LOG.warn("Formation adapter failed for {}",blueprint.formation(),ex);
            player.sendSystemMessage(Component.translatable("multiblock_builder.formation_failed"));
        }
        return false;
    }
}
