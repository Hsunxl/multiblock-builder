package io.github.hsunxl.multiblockbuilder.client;

import com.google.gson.*;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import io.github.hsunxl.multiblockbuilder.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.commands.Commands;
import net.minecraft.world.phys.*;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

@Mod(value=BuilderMod.ID,dist=Dist.CLIENT)
public final class BuilderClient {
    private static Blueprint preview;
    private static java.util.List<java.util.List<Blueprint.Cell>> rotations=java.util.List.of();
    private static boolean captureOnStartDone;
    public BuilderClient(IEventBus bus) {
        Network.receiver=BuilderClient::receive;
        NeoForge.EVENT_BUS.addListener(BuilderClient::render);
        NeoForge.EVENT_BUS.addListener(BuilderClient::clientTick);
        NeoForge.EVENT_BUS.addListener(BuilderClient::clientCommands);
    }

    private static void clientTick(ClientTickEvent.Post event) {
        if (captureOnStartDone || System.getProperty("mbuilder.captureOnStart") == null) return;
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        captureOnStartDone = true;
        GuideCapture.run(mc,10);
    }

    private static void clientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("mbuilder-capture")
                .executes(ctx -> { GuideCapture.run(Minecraft.getInstance(),10); return 1; })
                .then(Commands.argument("min",IntegerArgumentType.integer(1,4096))
                        .executes(ctx -> {
                            GuideCapture.run(Minecraft.getInstance(),IntegerArgumentType.getInteger(ctx,"min"));
                            return 1;
                        })));
    }
    private static void receive(String json) {
        try {
            var root=JsonParser.parseString(json).getAsJsonObject();
            if(root.has("catalog")) Minecraft.getInstance().setScreen(new CatalogScreen(root.getAsJsonArray("catalog")));
            else {
                preview=root.has("blueprint")?Blueprint.parse(ResourceLocation.parse(root.get("id").getAsString()),root.getAsJsonObject("blueprint")):null;
                rotations=preview==null?java.util.List.of():java.util.stream.IntStream.range(0,4).mapToObj(preview::rotated).toList();
            }
        } catch(RuntimeException ex) { preview=null; BuilderMod.LOG.warn("Invalid builder display data",ex); }
    }
    private static void render(RenderLevelStageEvent event) {
        if(event.getStage()!=RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || preview==null) return;
        var mc=Minecraft.getInstance(); if(mc.level==null || mc.player==null) return;
        var tool=mc.player.getMainHandItem().is(BuilderMod.TOOL.get())?mc.player.getMainHandItem():mc.player.getOffhandItem();
        if(!tool.is(BuilderMod.TOOL.get())) return;
        var t=Selection.read(tool); if(!t.getString("blueprint").equals(preview.id().toString())) return;
        var origin=Selection.origin(t);
        if(origin!=null && !t.getString("dimension").equals(mc.level.dimension().location().toString())) return;
        if(origin==null) {
            if(!(mc.hitResult instanceof BlockHitResult hit) || hit.getType()!=HitResult.Type.BLOCK) return;
            origin=hit.getBlockPos().relative(hit.getDirection());
        }
        int turns=t.getInt("rotation"), layer=t.contains("layer")?t.getInt("layer"):-1;
        var pose=event.getPoseStack(); var camera=event.getCamera().getPosition();
        var buffers=mc.renderBuffers().bufferSource(); var lines=buffers.getBuffer(RenderType.lines());
        pose.pushPose(); pose.translate(-camera.x,-camera.y,-camera.z);
        int w=(turns%2==0)?preview.width():preview.depth(), d=(turns%2==0)?preview.depth():preview.width();
        LevelRenderer.renderLineBox(pose,lines,new AABB(origin.getX(),origin.getY(),origin.getZ(),origin.getX()+w,origin.getY()+preview.height(),origin.getZ()+d),0.2F,0.8F,1F,0.8F);
        if(Screen.hasControlDown() || layer>=0) for(var cell:rotations.get(Math.floorMod(turns,4))) {
            if(layer>=0 && cell.offset().getY()!=layer) continue;
            var pos=origin.offset(cell.offset()); var box=new AABB(pos).inflate(0.002);
            if(!event.getFrustum().isVisible(box)) continue;
            var current=mc.level.getBlockState(pos); boolean match=current.equals(cell.state()); boolean blocked=!current.isAir()&&!match;
            LevelRenderer.renderLineBox(pose,lines,box,blocked?1F:0.2F,blocked?0.15F:match?1F:0.7F,match?0.2F:blocked?0.15F:1F,0.75F);
        }
        pose.popPose(); buffers.endBatch(RenderType.lines());
    }
}
