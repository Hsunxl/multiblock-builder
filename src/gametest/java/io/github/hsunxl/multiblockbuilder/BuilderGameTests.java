package io.github.hsunxl.multiblockbuilder;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import com.google.gson.JsonParser;
import java.util.LinkedHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.*;
import java.util.function.Consumer;

@GameTestHolder(BuilderMod.ID)
@PrefixGameTestTemplate(false)
public final class BuilderGameTests {
    private static Blueprint blueprint() {
        return Blueprint.parse(ResourceLocation.parse("multiblock_builder:test"),JsonParser.parseString("""
            {"size":[2,1,1],"survival":true,"blocks":[
            {"pos":[0,0,0],"block":"minecraft:stone"},
            {"pos":[1,0,0],"block":"minecraft:stone"}]}
            """).getAsJsonObject());
    }
    private static ServerPlayer player(GameTestHelper h) {
        var p=h.makeMockServerPlayerInLevel();
        var at=h.absolutePos(new BlockPos(1,2,1)); p.setPos(at.getX(),at.getY(),at.getZ());
        p.getAbilities().instabuild=false; p.getAbilities().mayBuild=true;
        p.getInventory().clearContent(); p.getInventory().setItem(0,new ItemStack(BuilderMod.TOOL.get()));
        return p;
    }
    private static BlockPos anchor(GameTestHelper h) {
        h.setBlock(1,2,1,Blocks.AIR); h.setBlock(2,2,1,Blocks.AIR);
        return h.absolutePos(new BlockPos(1,2,1));
    }
    @GameTest(template="empty") public static void survivalConsumesExactlyOnce(GameTestHelper h) {
        var p=player(h); var anchor=anchor(h); p.getInventory().setItem(1,new ItemStack(Items.STONE,4));
        h.assertTrue(Placement.build(p,p.getMainHandItem(),blueprint(),anchor,0),"Build should succeed");
        h.assertTrue(p.getInventory().getItem(1).getCount()==2,"Exactly 2 material items charged");
        h.assertTrue(Placement.build(p,p.getMainHandItem(),blueprint(),anchor,0),"Existing correct blocks should be skipped");
        h.assertTrue(p.getInventory().getItem(1).getCount()==2,"No double charge");
        h.assertBlockPresent(Blocks.STONE,1,2,1); h.assertBlockPresent(Blocks.STONE,2,2,1); h.succeed();
    }
    @GameTest(template="empty") public static void missingMaterialsDoNotPlace(GameTestHelper h) {
        var p=player(h); var anchor=anchor(h); p.getInventory().setItem(1,new ItemStack(Items.STONE,1));
        h.assertTrue(!Placement.build(p,p.getMainHandItem(),blueprint(),anchor,0),"Missing materials must fail");
        h.assertTrue(p.getInventory().getItem(1).getCount()==1,"No charge on failure");
        h.assertBlockPresent(Blocks.AIR,1,2,1); h.assertBlockPresent(Blocks.AIR,2,2,1); h.succeed();
    }
    @GameTest(template="empty") public static void obstructionIsPreserved(GameTestHelper h) {
        var p=player(h); var anchor=anchor(h); p.getInventory().setItem(1,new ItemStack(Items.STONE,2));
        h.setBlock(2,2,1,Blocks.DIAMOND_BLOCK);
        h.assertTrue(!Placement.build(p,p.getMainHandItem(),blueprint(),anchor,0),"Blocked target must fail");
        h.assertTrue(p.getInventory().getItem(1).getCount()==2,"No charge");
        h.assertBlockPresent(Blocks.AIR,1,2,1); h.assertBlockPresent(Blocks.DIAMOND_BLOCK,2,2,1); h.succeed();
    }
    @GameTest(template="empty") public static void protectionRollsBack(GameTestHelper h) {
        var p=player(h); var anchor=anchor(h); p.getInventory().setItem(1,new ItemStack(Items.STONE,2));
        Consumer<BlockEvent.EntityPlaceEvent> cancel=e->{if(e.getEntity()==p) e.setCanceled(true);};
        NeoForge.EVENT_BUS.addListener(cancel);
        try {
            h.assertTrue(!Placement.build(p,p.getMainHandItem(),blueprint(),anchor,0),"Protection cancellation must fail");
            h.assertTrue(p.getInventory().getItem(1).getCount()==2,"No charge after rollback");
            h.assertBlockPresent(Blocks.AIR,1,2,1); h.assertBlockPresent(Blocks.AIR,2,2,1);
        } finally { NeoForge.EVENT_BUS.unregister(cancel); }
        h.succeed();
    }
    @GameTest(template="empty") public static void meSourceIsTransactional(GameTestHelper h) {
        var storage=new FakeStorage(); storage.put(Items.STONE,2);
        var source=new MeSource(storage,null);
        var costs=new LinkedHashMap<Item,Integer>(); costs.put(Items.STONE,3);
        h.assertTrue(source.missing(costs).get(Items.STONE)==1,"me missing amount");
        h.assertTrue(!source.extract(costs),"short extraction must fail");
        h.assertTrue(storage.amount(Items.STONE)==2,"short extraction restores storage");
        storage.put(Items.STONE,5);
        h.assertTrue(source.extract(costs),"enough extraction succeeds");
        h.assertTrue(storage.amount(Items.STONE)==2,"exact charge");
        source.refund(costs);
        h.assertTrue(storage.amount(Items.STONE)==5,"refund restores");
        storage.put(Items.STONE,2); storage.put(Items.DIRT,1);
        costs.clear(); costs.put(Items.STONE,2); costs.put(Items.DIRT,2);
        h.assertTrue(!source.extract(costs),"second item shortage fails");
        h.assertTrue(storage.amount(Items.STONE)==2&&storage.amount(Items.DIRT)==1,"partial extraction rolled back");
        h.succeed();
    }
    @GameTest(template="empty") public static void invalidBlueprintRejected(GameTestHelper h) {
        h.assertTrue(Blueprints.INSTANCE.get("")==null,"Empty tool selection must be safe");
        h.assertTrue(Blueprints.INSTANCE.get("multiblock_builder:demo_frame")!=null,"Data pack reload must discover bundled blueprint");
        boolean rejected=false;
        try { Blueprint.parse(ResourceLocation.parse("test:bad"),JsonParser.parseString("""
            {"size":[1,1,1],"survival":true,"blocks":[{"pos":[0,0,0],"block":"minecraft:oak_door"}]}
            """).getAsJsonObject()); } catch(IllegalArgumentException ex) { rejected=true; }
        h.assertTrue(rejected,"Multi-cell item mapping must require review/creative"); h.succeed();
    }
    private static final class FakeStorage implements MEStorage {
        private final java.util.Map<AEKey,Long> values=new LinkedHashMap<>();
        void put(Item item,long amount) { values.put(AEItemKey.of(item),amount); }
        long amount(Item item) { return values.getOrDefault(AEItemKey.of(item),0L); }
        @Override public long extract(AEKey key,long amount,Actionable mode,IActionSource source) {
            long have=values.getOrDefault(key,0L), take=Math.min(have,amount);
            if(mode==Actionable.MODULATE) values.put(key,have-take);
            return take;
        }
        @Override public long insert(AEKey key,long amount,Actionable mode,IActionSource source) {
            if(mode==Actionable.MODULATE) values.merge(key,amount,Long::sum);
            return amount;
        }
        @Override public void getAvailableStacks(KeyCounter out) {}
        @Override public Component getDescription() { return Component.literal("fake"); }
    }
}
