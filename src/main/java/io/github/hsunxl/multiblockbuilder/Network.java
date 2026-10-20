package io.github.hsunxl.multiblockbuilder;

import com.google.gson.*;
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
    public record Data(String json) implements CustomPacketPayload {
        public static final Type<Data> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(BuilderMod.ID,"data"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Data> CODEC=StreamCodec.of(
            (buf,msg)->buf.writeUtf(msg.json,2_000_000),buf->new Data(buf.readUtf(2_000_000)));
        @Override public Type<Data> type() { return TYPE; }
    }
    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(Data.TYPE,Data.CODEC,(msg,context)->context.enqueueWork(()->receiver.accept(msg.json)));
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
    private static void send(ServerPlayer p,JsonObject root) { PacketDistributor.sendToPlayer(p,new Data(root.toString())); }
}
