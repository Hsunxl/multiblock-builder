package io.github.hsunxl.multiblockbuilder;

import com.google.gson.JsonParser;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.zip.*;
import net.minecraft.nbt.*;

public final class GuideImportTest {
    private GuideImportTest() {}

    public static void run() throws Exception {
        Path root = Files.createTempDirectory("mbuilder-guide-test");
        try {
            byte[] nbt = nbt();
            Path jar = root.resolve("atm-structure-library-test.jar");
            try (var out = new ZipOutputStream(Files.newOutputStream(jar))) {
                entry(out,"assets/test/guides/test/structures/demo.nbt",nbt);
                entry(out,"assets/test/guides/test/structures/broken.nbt",new byte[]{1,2,3});
                entry(out,"assets/test/guides/test/page.md",("""
                        ---
                        title: "Test Guide"
                        ---
                        <ImportStructure src="structures/demo.nbt" />
                        """).getBytes(StandardCharsets.UTF_8));
            }

            Path datapacks = root.resolve("datapacks");
            Files.createDirectories(datapacks);
            var result = GuideImport.importJar(jar,datapacks,"guide_import","*");
            assert result.converted() == 1 : "one valid template";
            assert result.skipped() == 1 : "one broken template";
            Path json = datapacks.resolve("mbuilder_import/data/guide_import/multiblock_blueprints/demo.json");
            assert Files.isRegularFile(json) : "blueprint json exists";
            assert Files.isRegularFile(datapacks.resolve("mbuilder_import/.mbuilder-import")) : "marker exists";
            assert Files.isRegularFile(datapacks.resolve("mbuilder_import/import-report.json")) : "report exists";
            var bp = JsonParser.parseString(Files.readString(json)).getAsJsonObject();
            assert bp.get("title").getAsString().equals("Test Guide") : "markdown title";
            assert !bp.get("survival").getAsBoolean() : "import defaults to creative";
            assert bp.getAsJsonArray("blocks").size() == 2 : "air omitted, two cells";
            assert bp.getAsJsonArray("size").get(0).getAsInt() == 3 : "size preserved";

            boolean refused = false;
            try { GuideImport.importJar(jar,datapacks,"guide_import","nomatch*"); }
            catch (IOException ex) { refused = true; }
            assert refused : "empty import must fail";
            assert Files.isRegularFile(json) : "failed import must not replace previous pack";

            assert GuideImport.globMatches("ae_family/*","ae_family/x");
            assert !GuideImport.globMatches("ae_family/*","ie/x");
            assert GuideImport.normalizePosix("assets/x/../y").equals("assets/y");
            assert GuideImport.normalizePosix("../x") == null;

            Path guide = Path.of("../../../mods/atm-structure-library-1.0.0.jar");
            if (Files.isRegularFile(guide)) {
                var real = GuideImport.importJar(guide,root.resolve("real-datapacks"),"guide_import","*");
                assert real.converted() == 154 : "installed guide has 154 static templates, got " + real.converted();
                Path ultimate = root.resolve("real-datapacks/mbuilder_import/data/guide_import/multiblock_blueprints/ae_family/ae_plus_ultimate.json");
                assert Files.isRegularFile(ultimate) : "known ultimate template exists";
                var ultimateJson = JsonParser.parseString(Files.readString(ultimate)).getAsJsonObject();
                assert !ultimateJson.get("title").getAsString().isBlank() : "real template title";
                assert ultimateJson.getAsJsonArray("blocks").size() > 100 : "real template cells";
                System.out.println("PASS real guide import: " + real.converted() + " templates, "
                        + ultimateJson.getAsJsonArray("blocks").size() + " cells in ultimate");

            }

            System.out.println("PASS guide import conversion, titles, skip/report and no-clobber tests");
        } finally {
            delete(root);
        }
    }

    private static byte[] nbt() throws IOException {
        var root = new CompoundTag();
        var size = new ListTag();
        size.add(IntTag.valueOf(3)); size.add(IntTag.valueOf(1)); size.add(IntTag.valueOf(1));
        root.put("size",size);
        var palette = new ListTag();
        var stone = new CompoundTag(); stone.putString("Name","minecraft:stone"); palette.add(stone);
        var air = new CompoundTag(); air.putString("Name","minecraft:air"); palette.add(air);
        root.put("palette",palette);
        var blocks = new ListTag();
        blocks.add(block(0,0,0,0,false));
        blocks.add(block(1,0,0,0,true));
        blocks.add(block(2,0,0,1,false));
        root.put("blocks",blocks);
        var out = new ByteArrayOutputStream();
        NbtIo.writeCompressed(root,out);
        return out.toByteArray();
    }

    private static CompoundTag block(int x,int y,int z,int state,boolean withNbt) {
        var block = new CompoundTag();
        var pos = new ListTag();
        pos.add(IntTag.valueOf(x)); pos.add(IntTag.valueOf(y)); pos.add(IntTag.valueOf(z));
        block.put("pos",pos);
        block.putInt("state",state);
        if (withNbt) block.put("nbt",new CompoundTag());
        return block;
    }

    private static void entry(ZipOutputStream out,String name,byte[] data) throws IOException {
        out.putNextEntry(new ZipEntry(name));
        out.write(data);
        out.closeEntry();
    }

    private static void delete(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var stream = Files.walk(path)) {
            for (Path p : stream.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
}
