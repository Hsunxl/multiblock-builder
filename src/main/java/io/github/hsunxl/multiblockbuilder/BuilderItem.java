package io.github.hsunxl.multiblockbuilder;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import java.util.List;

public final class BuilderItem extends Item {
    public BuilderItem(Properties properties) { super(properties); }
    @Override public InteractionResult useOn(UseOnContext context) {
        if (context.getLevel().isClientSide) return InteractionResult.SUCCESS;
        if (!(context.getPlayer() instanceof ServerPlayer p)) return InteractionResult.FAIL;
        var stack=context.getItemInHand();
        var tag=Selection.read(stack);
        var blueprint=Blueprints.INSTANCE.get(tag.getString("blueprint"));
        if(blueprint==null) { Network.catalog(p); return InteractionResult.SUCCESS; }
        var origin=Selection.origin(tag);
        if(p.isShiftKeyDown() || origin==null || !tag.getString("dimension").equals(p.level().dimension().location().toString())) {
            origin=context.getClickedPos().relative(context.getClickedFace());
            tag.putLong("origin",origin.asLong());
            tag.putString("dimension",p.level().dimension().location().toString());
            Selection.write(stack,tag);
            p.displayClientMessage(Component.translatable("multiblock_builder.anchor",origin.toShortString()),true);
            Network.preview(p,stack);
            return InteractionResult.SUCCESS;
        }
        boolean built=Placement.build(p,stack,blueprint,origin,tag.getInt("rotation"));
        Network.preview(p,stack);
        return built?InteractionResult.SUCCESS:InteractionResult.FAIL;
    }
    @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        var stack=player.getItemInHand(hand);
        if(player instanceof ServerPlayer p) {
            if(p.isShiftKeyDown()) { Selection.clearOrigin(stack); Network.preview(p,stack); }
            else Network.catalog(p);
        }
        return InteractionResultHolder.sidedSuccess(stack,level.isClientSide);
    }
    @Override public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("multiblock_builder.tooltip"));
        String id=Selection.read(stack).getString("blueprint");
        if(!id.isEmpty()) tooltip.add(Component.literal(id));
    }
}
