package io.github.hsunxl.multiblockbuilder;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.*;
import com.mojang.brigadier.exceptions.*;
import net.minecraft.commands.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import static net.minecraft.commands.Commands.*;

public final class BuilderCommands {
    private static ItemStack held(ServerPlayer p) throws CommandSyntaxException {
        for(var stack:java.util.List.of(p.getMainHandItem(),p.getOffhandItem())) if(stack.is(BuilderMod.TOOL.get())) return stack;
        throw new SimpleCommandExceptionType(Component.translatable("multiblock_builder.hold")).create();
    }
    private static void runImport(CommandSourceStack source, String include) {
        try {
            var result=GuideImport.run(source,include);
            source.sendSuccess(()->Component.translatable("multiblock_builder.import.done",
                    result.converted(),result.skipped(),result.jar().getFileName().toString()),true);
        } catch (Exception ex) {
            BuilderMod.LOG.error("Guide import failed",ex);
            source.sendFailure(Component.translatable("multiblock_builder.import.failed",
                    ex.getMessage()==null?ex.getClass().getSimpleName():ex.getMessage()));
        }
    }
    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(literal("mbuilder")
            .executes(c->{ Network.catalog(c.getSource().getPlayerOrException()); return 1; })
            .then(literal("list").executes(c->{
                for(var b:Blueprints.INSTANCE.all()) c.getSource().sendSuccess(()->Component.literal(b.id()+" · "+b.title()+(b.survival()?"":" [creative]")),false);
                return 1;
            }))
            .then(literal("select").then(argument("id",StringArgumentType.word()).suggests((c,b)->SharedSuggestionProvider.suggest(Blueprints.INSTANCE.all().stream().map(v->v.id().toString()),b)).executes(c->{
                var p=c.getSource().getPlayerOrException(); var stack=held(p);
                String id=StringArgumentType.getString(c,"id"); var blueprint=Blueprints.INSTANCE.get(id);
                if(blueprint==null) throw new SimpleCommandExceptionType(Component.literal("Unknown blueprint")).create();
                var t=Selection.read(stack); t.putString("blueprint",id); t.remove("origin"); t.putInt("rotation",0); t.putInt("layer",-1); Selection.write(stack,t);
                Network.preview(p,stack); p.sendSystemMessage(Component.literal(blueprint.title())); return 1;
            })))
            .then(literal("rotate").executes(c->{
                var p=c.getSource().getPlayerOrException(); var stack=held(p); var t=Selection.read(stack);
                t.putInt("rotation",Math.floorMod(t.getInt("rotation")+1,4)); Selection.write(stack,t); Network.preview(p,stack); return 1;
            }))
            .then(literal("layer").then(argument("y",IntegerArgumentType.integer(-1,63)).executes(c->{
                var p=c.getSource().getPlayerOrException(); var stack=held(p); var t=Selection.read(stack);
                t.putInt("layer",IntegerArgumentType.getInteger(c,"y")); Selection.write(stack,t); Network.preview(p,stack); return 1;
            })))
            .then(literal("clear").executes(c->{var p=c.getSource().getPlayerOrException(); var stack=held(p); Selection.clearOrigin(stack); Network.preview(p,stack); return 1;}))
            .then(literal("unbind").executes(c->{
                var p=c.getSource().getPlayerOrException(); var stack=held(p);
                Selection.clearInterface(stack); p.sendSystemMessage(Component.translatable("multiblock_builder.interface_cleared")); return 1;
            }))
            .then(literal("import").requires(s->s.hasPermission(2))
                .executes(c->{ runImport(c.getSource(),"*"); return 1; })
                .then(argument("include",StringArgumentType.greedyString())
                    .executes(c->{ runImport(c.getSource(),StringArgumentType.getString(c,"include")); return 1; })))
            .then(literal("materials").executes(c->{
                var p=c.getSource().getPlayerOrException(); var t=Selection.read(held(p)); var b=Blueprints.INSTANCE.get(t.getString("blueprint"));
                if(b==null) return 0;
                var costs=new java.util.LinkedHashMap<net.minecraft.world.item.Item,Integer>();
                for(var cell:b.cells()) costs.merge(cell.state().getBlock().asItem(),1,Integer::sum);
                for(var e:costs.entrySet()) p.sendSystemMessage(Component.translatable("multiblock_builder.material",e.getKey().getDescription(),e.getValue()));
                return 1;
            })));
    }
}
