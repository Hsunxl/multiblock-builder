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
    /**
     * End-to-end proof that survival mode can really build a bundled blueprint: a plain (non-creative)
     * player places {@code guide_import:.../ae_controllers_ring} and pays exactly its eight controllers
     * from the main inventory. This is the case the old 0.3.x build refused with
     * "This blueprint is creative-only".
     */
    @GameTest(template="empty") public static void bundledSurvivalBlueprintBuildsInSurvival(GameTestHelper h) {
        var blueprint=Blueprints.INSTANCE.get("guide_import:ae_family/ae_controllers_ring");
        h.assertTrue(blueprint!=null,"Bundled AE controller ring must load on a server that only has AE2");
        h.assertTrue(blueprint.survival(),"Bundled AE controller ring must be survival-buildable");
        h.assertTrue(blueprint.cells().size()==8,"AE controller ring keeps its eight cells");
        var p=player(h); var at=anchor(h);
        var controller=appeng.core.definitions.AEBlocks.CONTROLLER.block().asItem();
        p.getInventory().setItem(1,new ItemStack(controller,8));
        h.assertTrue(Placement.build(p,p.getMainHandItem(),blueprint,at,0),"Survival build of a bundled blueprint must succeed");
        h.assertTrue(p.getInventory().countItem(controller)==0,"Exactly eight controllers charged");
        var placed=appeng.core.definitions.AEBlocks.CONTROLLER.block();
        h.assertBlockPresent(placed,1,2,1);
        h.assertBlockPresent(placed,3,2,3);
        h.assertBlockPresent(Blocks.AIR,2,2,2);
        h.succeed();
    }

    /** A creative-only layout must be refused for a survival player and must not charge anything. */
    @GameTest(template="empty") public static void creativeOnlyBlueprintRefusedInSurvival(GameTestHelper h) {
        var creativeOnly=Blueprint.parse(ResourceLocation.parse("test:creative"),
                JsonParser.parseString("""
                    {"size":[2,1,1],"survival":false,"blocks":[
                    {"pos":[0,0,0],"block":"minecraft:stone"},
                    {"pos":[1,0,0],"block":"minecraft:stone"}]}
                    """).getAsJsonObject());
        var p=player(h); var at=anchor(h);
        p.getInventory().setItem(1,new ItemStack(Items.STONE,4));
        // plan() is the gate that refuses creative-only layouts for players without instabuild.
        boolean refused=false;
        try { Placement.plan(p,creativeOnly,at,0); }
        catch (Placement.BuildError error) {
            refused=true;
            String key="multiblock_builder.error.creative_only";
            h.assertTrue(!error.text().getString().equals(key),"Refusal must be a translated message, not a raw key");
            h.assertTrue(!error.text().getString().contains("creative-only"),"Old raw English text must be gone");
        }
        h.assertTrue(refused,"Creative-only layout must be refused for a survival player");
        h.assertTrue(!Placement.build(p,p.getMainHandItem(),creativeOnly,at,0),"Build must report failure");
        h.assertTrue(p.getInventory().countItem(Items.STONE)==4,"No materials charged on refusal");
        h.assertBlockPresent(Blocks.AIR,1,2,1);
        h.assertBlockPresent(Blocks.AIR,2,2,1);
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
    @GameTest(template="empty") public static void previewIncludesAllSixSides(GameTestHelper h) {
        var json=new com.google.gson.JsonObject();
        json.add("size",JsonParser.parseString("[3,3,3]")); json.addProperty("survival",true);
        var blocks=new com.google.gson.JsonArray();
        for(int x=0;x<3;x++) for(int y=0;y<3;y++) for(int z=0;z<3;z++) {
            var cell=new com.google.gson.JsonObject();
            cell.add("pos",JsonParser.parseString("["+x+","+y+","+z+"]"));
            cell.addProperty("block","minecraft:stone"); blocks.add(cell);
        }
        json.add("blocks",blocks);
        var scene=Network.createScene(Blueprint.parse(ResourceLocation.parse("test:shell"),json));
        var positions=new java.util.HashSet<BlockPos>();
        for(int i=0;i<scene.cells().length;i+=4) positions.add(new BlockPos(
                scene.cells()[i]&255,scene.cells()[i+1]&255,scene.cells()[i+2]&255));
        h.assertTrue(positions.size()==26,"Solid 3x3x3 must send all 26 shell cells, not just positive sides");
        for(int x=0;x<3;x++) for(int y=0;y<3;y++) for(int z=0;z<3;z++)
            h.assertTrue(positions.contains(new BlockPos(x,y,z))!=(x==1&&y==1&&z==1),"Shell membership");
        h.succeed();
    }
    @GameTest(template="empty") public static void inventoryPartialLayout(GameTestHelper h) {
        var p=player(h); var at=anchor(h);
        h.setBlock(1,2,1,Blocks.STONE);
        p.getInventory().setItem(9,new ItemStack(Items.STONE,1));
        p.getInventory().setItem(35,new ItemStack(Items.STONE,1));
        h.assertTrue(Placement.build(p,p.getMainHandItem(),blueprint(),at,0),"Partial layout succeeds");
        h.assertTrue(p.getInventory().countItem(Items.STONE)==1,"Only missing block charged across main inventory");
        h.assertBlockPresent(Blocks.STONE,2,2,1); h.succeed();
    }
    @GameTest(template="empty") public static void inventoryReadsSplitStacks(GameTestHelper h) {
        var p=player(h); var at=anchor(h);
        p.getInventory().setItem(9,new ItemStack(Items.STONE,1));
        p.getInventory().setItem(35,new ItemStack(Items.STONE,1));
        h.assertTrue(Placement.build(p,p.getMainHandItem(),blueprint(),at,0),"Split main-inventory stacks build");
        h.assertTrue(p.getInventory().countItem(Items.STONE)==0,"Both stacks charged exactly");
        h.assertBlockPresent(Blocks.STONE,1,2,1); h.assertBlockPresent(Blocks.STONE,2,2,1); h.succeed();
    }
    @GameTest(template="empty") public static void customizedInventoryItemsArePreserved(GameTestHelper h) {
        var p=player(h); var at=anchor(h); var named=new ItemStack(Items.STONE,8);
        named.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,Component.literal("Keep me"));
        p.getInventory().setItem(1,named);
        h.assertTrue(!Placement.build(p,p.getMainHandItem(),blueprint(),at,0),"Customized items are not ordinary building materials");
        h.assertTrue(named.getCount()==8,"Customized stack preserved");
        h.assertBlockPresent(Blocks.AIR,1,2,1); h.succeed();
    }
    @GameTest(template="empty") public static void missingBoundInterfaceNeverFallsBack(GameTestHelper h) {
        var p=player(h); var at=anchor(h);
        p.getInventory().setItem(1,new ItemStack(Items.STONE,8));
        Selection.bindInterface(p.getMainHandItem(),p.level().dimension(),at.above(2));
        h.assertTrue(!Placement.build(p,p.getMainHandItem(),blueprint(),at,0),"Missing interface fails safely");
        h.assertTrue(p.getInventory().countItem(Items.STONE)==8,"No silent inventory fallback");
        h.assertBlockPresent(Blocks.AIR,1,2,1); h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=200) public static void realMeGridBuildsAndChargesOnce(GameTestHelper h) {
        realMeBuild(h,"success");
    }
    @GameTest(template="empty",timeoutTicks=200) public static void realMeShortageDoesNotUseBackpack(GameTestHelper h) {
        realMeBuild(h,"shortage");
    }
    @GameTest(template="empty",timeoutTicks=200) public static void realMeProtectionPreservesStorage(GameTestHelper h) {
        realMeBuild(h,"protection");
    }
    private static void realMeBuild(GameTestHelper h,String scenario) {
        var p=player(h); var at=anchor(h); var tool=p.getMainHandItem();
        h.setBlock(1,1,3,appeng.core.definitions.AEBlocks.INTERFACE.block());
        h.setBlock(2,1,3,appeng.core.definitions.AEBlocks.DRIVE.block());
        h.setBlock(3,1,3,appeng.core.definitions.AEBlocks.CREATIVE_ENERGY_CELL.block());
        var iface=(appeng.blockentity.misc.InterfaceBlockEntity)h.getBlockEntity(new BlockPos(1,1,3));
        var drive=(appeng.blockentity.storage.DriveBlockEntity)h.getBlockEntity(new BlockPos(2,1,3));
        drive.getInternalInventory().setItemDirect(0,appeng.core.definitions.AEItems.ITEM_CELL_1K.stack());
        Selection.bindInterface(tool,p.level().dimension(),h.absolutePos(new BlockPos(1,1,3)));
        p.getInventory().setItem(1,new ItemStack(Items.STONE,8));
        h.runAfterDelay(80,()->{
            h.assertTrue(iface.getMainNode().isActive(),"Real interface must be powered and channeled");
            var grid=iface.getMainNode().getGrid();
            h.assertTrue(grid==drive.getMainNode().getGrid(),"Interface and drive share real grid");
            var storage=grid.getStorageService().getInventory();
            var action=new appeng.me.helpers.PlayerSource(p); var key=AEItemKey.of(Items.STONE);
            int initial=scenario.equals("shortage")?1:5;
            h.assertTrue(storage.insert(key,initial,Actionable.MODULATE,action)==initial,"Insert into real 1K cell");
            h.assertTrue(Materials.resolve(p,tool) instanceof MeSource,"Bound source must resolve to ME");
            Consumer<BlockEvent.EntityPlaceEvent> cancel=e->{if(e.getEntity()==p) e.setCanceled(true);};
            boolean protect=scenario.equals("protection");
            if(protect) NeoForge.EVENT_BUS.addListener(cancel);
            try {
                boolean success=scenario.equals("success");
                h.assertTrue(Placement.build(p,tool,blueprint(),at,0)==success,"Build outcome: "+scenario);
                h.assertTrue(storage.extract(key,100,Actionable.SIMULATE,action)==(success?3:initial),"Exact ME charge or preservation");
                h.assertTrue(p.getInventory().countItem(Items.STONE)==8,"Backpack untouched while bound to ME");
                h.assertBlockPresent(success?Blocks.STONE:Blocks.AIR,1,2,1);
                h.assertBlockPresent(success?Blocks.STONE:Blocks.AIR,2,2,1);
                if(success) {
                    h.assertTrue(Placement.build(p,tool,blueprint(),at,0),"Repeated ME build succeeds");
                    h.assertTrue(storage.extract(key,100,Actionable.SIMULATE,action)==3,"No double ME charge");
                }
            } finally { if(protect) NeoForge.EVENT_BUS.unregister(cancel); }
            h.succeed();
        });
    }
    @GameTest(template="empty") public static void bundledBlueprintsShipInsideTheJar(GameTestHelper h) {
        // The GameTest server only loads AE2 + this mod, so most ATM10 blocks are missing and those
        // blueprints are skipped at parse time. Inspect the resource pack instead: every bundled file
        // must be present and well formed, whatever the current mod set can resolve.
        var resources=h.getLevel().getServer().getResourceManager()
                .listResources("multiblock_blueprints",p->p.getPath().endsWith(".json"));
        h.assertTrue(!resources.isEmpty(),"Bundled guide blueprints are visible through the resource manager");
        long guideFiles=resources.keySet().stream().filter(id->id.getNamespace().startsWith("guide_")).count();
        h.assertTrue(guideFiles>=385,"Mod jar must bundle the whole guide library, got "+guideFiles);
        long creative=0;
        for(var entry:resources.entrySet()) {
            if(!entry.getKey().getNamespace().startsWith("guide_")) continue;
            var json=readJson(entry.getValue());
            h.assertTrue(json.has("size")&&json.has("blocks"),"Bundled blueprint is well formed: "+entry.getKey());
            if(json.has("survival")&&!json.get("survival").getAsBoolean()) creative++;
        }
        h.assertTrue(creative==19,"Exactly 19 creative-only layouts ship in the jar, got "+creative);
        var ultimate=resources.get(ResourceLocation.fromNamespaceAndPath("guide_import","multiblock_blueprints/ae_family/ae_plus_ultimate.json"));
        h.assertTrue(ultimate!=null,"Ultimate matrix template ships in the jar");
        var ultimateJson=readJson(ultimate);
        h.assertTrue(Formation.ULTIMATE_SUPER_ASSEMBLER_MATRIX.equals(ultimateJson.has("formation")?ultimateJson.get("formation").getAsString():""),
                "Ultimate matrix keeps its formation adapter");
        h.assertTrue(ultimateJson.getAsJsonArray("blocks").size()>100,"Ultimate matrix keeps its cells");
        var demo=Blueprints.INSTANCE.get("multiblock_builder:demo_frame");
        h.assertTrue(demo!=null&&demo.survival(),"Bundled demo frame still loads");
        h.succeed();
    }
    private static com.google.gson.JsonObject readJson(net.minecraft.server.packs.resources.Resource resource) {
        try(var reader=resource.openAsReader()) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch(java.io.IOException ex) { throw new AssertionError("Unreadable bundled blueprint",ex); }
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
