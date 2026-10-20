package io.github.hsunxl.multiblockbuilder;

import com.google.gson.*;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public final class Network {
    public static Consumer<String> receiver = value -> {};
    public static Consumer<Scene> sceneReceiver = scene -> {};

    public record Data(String json) implements CustomPacketPayload {
        public static final Type<Data> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(BuilderMod.ID,"data"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Data> CODEC=StreamCodec.of(
            (buf,msg)->buf.writeUtf(msg.json,2_000_000),buf->new Data(buf.readUtf(2_000_000)));
        @Override public Type<Data> type() { return TYPE; }
    }

    /** Client asks the server for one compact voxel preview while hovering a catalog row. */
    public record PreviewRequest(String id) implements CustomPacketPayload {
        public static final Type<PreviewRequest> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(BuilderMod.ID,"preview_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf,PreviewRequest> CODEC=StreamCodec.of(
            (buf,msg)->buf.writeUtf(msg.id,256),buf->new PreviewRequest(buf.readUtf(256)));
        @Override public Type<PreviewRequest> type() { return TYPE; }
    }

    /** Block ids plus one byte per exposed block; the client textures them with their own sprite. */
    public record Scene(String id,int width,int height,int depth,String[] palette,byte[] cells) implements CustomPacketPayload {
        public static final Type<Scene> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(BuilderMod.ID,"scene"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Scene> CODEC=StreamCodec.of(
            (buf,msg)->{
                buf.writeUtf(msg.id,256);
                buf.writeVarInt(msg.width);
                buf.writeVarInt(msg.height);
                buf.writeVarInt(msg.depth);
                buf.writeVarInt(msg.palette.length);
                for(String block:msg.palette) buf.writeUtf(block,128);
                buf.writeByteArray(msg.cells);
            },
            buf->{
                String id=buf.readUtf(256);
                int width=buf.readVarInt(),height=buf.readVarInt(),depth=buf.readVarInt();
                String[] palette=new String[buf.readVarInt()];
                for(int i=0;i<palette.length;i++) palette[i]=buf.readUtf(128);
                return new Scene(id,width,height,depth,palette,buf.readByteArray());
            });
        @Override public Type<Scene> type() { return TYPE; }
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar=event.registrar("1");
        registrar.playToClient(Data.TYPE,Data.CODEC,(msg,context)->context.enqueueWork(()->receiver.accept(msg.json)));
        registrar.playToClient(Scene.TYPE,Scene.CODEC,(msg,context)->context.enqueueWork(()->sceneReceiver.accept(msg)));
        registrar.playToServer(PreviewRequest.TYPE,PreviewRequest.CODEC,(msg,context)->context.enqueueWork(()->{
            if(context.player() instanceof ServerPlayer p) {
                var blueprint=Blueprints.INSTANCE.get(msg.id());
                if(blueprint!=null) previewScene(p,blueprint);
            }
        }));
    }
    public static void catalog(ServerPlayer p) {
        var root=new JsonObject(); var entries=new JsonArray();
        for(var b:Blueprints.INSTANCE.all().stream().limit(512).toList()) {
            var entry=new JsonObject();
            entry.addProperty("id",b.id().toString());
            entry.addProperty("title",b.title());
            entry.addProperty("survival",b.survival());
            entry.addProperty("cells",b.cells().size());
            entry.addProperty("formation",!b.formation().isEmpty());
            var size=new JsonArray(); size.add(b.width()); size.add(b.height()); size.add(b.depth()); entry.add("size",size);
            var icon=BuiltInRegistries.ITEM.getKey(b.cells().get(0).state().getBlock().asItem());
            entry.addProperty("icon",icon.toString());
            entries.add(entry);
        }
        root.add("catalog",entries); send(p,root);
    }
    public static void preview(ServerPlayer p, ItemStack stack) {
        var root=new JsonObject(); var t=Selection.read(stack); var b=Blueprints.INSTANCE.get(t.getString("blueprint"));
        if(b!=null) { root.addProperty("id",b.id().toString()); root.add("blueprint",JsonParser.parseString(b.json())); }
        send(p,root);
    }
    /** Sends only the exposed shell, so even a 5,832-block matrix stays tiny. */
    public static void previewScene(ServerPlayer p,Blueprint b) {
        int w=b.width(),h=b.height(),d=b.depth();
        var occupied=new BitSet((w+2)*(h+2)*(d+2));
        for(var cell:b.cells()) {
            var o=cell.offset();
            occupied.set(index(o.getX(),o.getY(),o.getZ(),w,d));
        }
        var palette=new ArrayList<String>();
        var paletteIndex=new HashMap<ResourceLocation,Integer>();
        var out=new ByteArrayOutputStream(b.cells().size()*4);
        for(var cell:b.cells()) {
            var o=cell.offset(); int x=o.getX(),y=o.getY(),z=o.getZ();
            int faces=0;
            if(!occupied.get(index(x,y+1,z,w,d))) faces|=1;
            if(!occupied.get(index(x+1,y,z,w,d))) faces|=2;
            if(!occupied.get(index(x,y,z+1,w,d))) faces|=4;
            if(faces==0) continue;
            var blockId=BuiltInRegistries.BLOCK.getKey(cell.state().getBlock());
            Integer slot=paletteIndex.get(blockId);
            if(slot==null) {
                if(palette.size()>=256) slot=0;
                else { slot=palette.size(); palette.add(blockId.toString()); paletteIndex.put(blockId,slot); }
            }
            out.write(x); out.write(y); out.write(z); out.write(slot);
        }
        if(palette.isEmpty()) palette.add("minecraft:stone");
        var scene=new Scene(b.id().toString(),w,h,d,palette.toArray(String[]::new),out.toByteArray());
        PacketDistributor.sendToPlayer(p,scene);
    }
    private static int index(int x,int y,int z,int width,int depth) {
        return ((y+1)*(depth+2)+(z+1))*(width+2)+(x+1);
    }
    private static void send(ServerPlayer p,JsonObject root) { PacketDistributor.sendToPlayer(p,new Data(root.toString())); }
}
