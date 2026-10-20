package io.github.hsunxl.multiblockbuilder.client;

import com.google.gson.*;
import guideme.Guide;
import guideme.Guides;
import guideme.compiler.PageCompiler;
import guideme.compiler.ParsedGuidePage;
import guideme.document.block.LytVBox;
import guideme.libs.mdast.model.MdAstNode;
import guideme.libs.mdast.model.MdAstParent;
import guideme.libs.mdast.mdx.model.MdxJsxFlowElement;
import guideme.scene.GuidebookScene;
import guideme.scene.LytGuidebookScene;
import guideme.scene.SceneTagCompiler;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.storage.LevelResource;

/** Exports the guide's compiled StructureViewer scenes, including runtime-generated ones. */
public final class GuideCapture {
    public static final String GUIDE_ID = "atm_structures:library";
    private static final String NAMESPACE = "guide_capture";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private GuideCapture() {}

    public record Result(int pages,int scenes,int exported,int small,int failed,int tooLarge,Path pack,Path report) {}

    public static Result run(Minecraft minecraft,int minBlocks) {
        Guide guide = Guides.getById(ResourceLocation.parse(GUIDE_ID));
        if (guide == null) {
            message(minecraft,"未找到指南 " + GUIDE_ID);
            return new Result(0,0,0,0,0,0,null,null);
        }
        Path pack = resolvePack(minecraft);
        Path data = pack.resolve("data").resolve(NAMESPACE).resolve("multiblock_blueprints");
        try {
            deleteTree(pack.resolve("data").resolve(NAMESPACE));
            Files.createDirectories(data);
            if (!Files.exists(pack.resolve("pack.mcmeta"))) {
                var meta = new JsonObject();
                var packInfo = new JsonObject();
                packInfo.addProperty("pack_format",48);
                packInfo.addProperty("description","Multiblock Builder runtime guide capture");
                meta.add("pack",packInfo);
                Files.writeString(pack.resolve("pack.mcmeta"),GSON.toJson(meta)+"\n",StandardCharsets.UTF_8);
            }
        } catch (IOException ex) {
            message(minecraft,"无法创建捕获目录: " + ex.getMessage());
            return new Result(0,0,0,0,0,0,pack,null);
        }

        var reportRows = new JsonArray();
        int pages = 0, scenes = 0, exported = 0, small = 0, failed = 0, tooLarge = 0;
        for (ParsedGuidePage parsed : guide.getPages()) {
            pages++;
            String pageId = parsed.getId().toString();
            try {
                var tags = new ArrayList<MdxJsxFlowElement>();
                collectStructureTags(parsed.getAstRoot(),tags);
                if (tags.isEmpty()) continue;
                String title = pageTitle(parsed,pageId);
                String relative = relativePath(parsed.getId());
                var compiler = new PageCompiler(guide,guide.getExtensions(),parsed.getSourcePack(),parsed.getId(),"");
                for (int sceneIndex = 0; sceneIndex < tags.size(); sceneIndex++) {
                    scenes++;
                    var scene = compileScene(compiler,tags.get(sceneIndex));
                    if (scene == null) {
                        failed++;
                        var row = new JsonObject();
                        row.addProperty("page",pageId); row.addProperty("status","failed"); row.addProperty("error","scene did not compile");
                        reportRows.add(row);
                        continue;
                    }
                    var positions = scene.getLevel().getFilledBlocks().map(BlockPos::immutable).toList();
                    var cells = new ArrayList<Cell>();
                    for (var pos : positions) {
                        BlockState state = scene.getLevel().getBlockState(pos);
                        if (state.isAir()) continue;
                        cells.add(new Cell(pos,state));
                    }
                    if (cells.isEmpty()) {
                        failed++;
                        var row = new JsonObject();
                        row.addProperty("page",pageId); row.addProperty("status","failed"); row.addProperty("error","no display blocks");
                        reportRows.add(row);
                        continue;
                    }
                    if (cells.size() < minBlocks) {
                        small++;
                        var row = new JsonObject();
                        row.addProperty("page",pageId); row.addProperty("status","small"); row.addProperty("blocks",cells.size());
                        reportRows.add(row);
                        continue;
                    }
                    if (cells.size() > io.github.hsunxl.multiblockbuilder.Blueprint.MAX_CELLS) {
                        tooLarge++;
                        var row = new JsonObject();
                        row.addProperty("page",pageId); row.addProperty("status","too_large"); row.addProperty("blocks",cells.size());
                        reportRows.add(row);
                        continue;
                    }
                    int minX=Integer.MAX_VALUE,minY=Integer.MAX_VALUE,minZ=Integer.MAX_VALUE;
                    int maxX=Integer.MIN_VALUE,maxY=Integer.MIN_VALUE,maxZ=Integer.MIN_VALUE;
                    for (var cell : cells) {
                        minX=Math.min(minX,cell.pos.getX()); minY=Math.min(minY,cell.pos.getY()); minZ=Math.min(minZ,cell.pos.getZ());
                        maxX=Math.max(maxX,cell.pos.getX()); maxY=Math.max(maxY,cell.pos.getY()); maxZ=Math.max(maxZ,cell.pos.getZ());
                    }
                    int width=maxX-minX+1, height=maxY-minY+1, depth=maxZ-minZ+1;
                    if (width > 64 || height > 64 || depth > 64) {
                        tooLarge++;
                        var row = new JsonObject();
                        row.addProperty("page",pageId); row.addProperty("status","too_large");
                        row.addProperty("blocks",cells.size()); row.addProperty("size",width+"x"+height+"x"+depth);
                        reportRows.add(row);
                        continue;
                    }
                    String path = relative + (tags.size() > 1 ? "_" + (sceneIndex + 1) : "");
                    path = path.replaceAll("[^a-z0-9_./-]","_");
                    var json = new JsonObject();
                    json.addProperty("title",title);
                    var size = new JsonArray(); size.add(width); size.add(height); size.add(depth); json.add("size",size);
                    json.addProperty("survival",true);
                    if (path.endsWith("ae_family/ae_plus_ultimate")) json.addProperty("formation","extendedae_plus:ultimate_super_assembler_matrix");
                    var blocks = new JsonArray();
                    cells.sort(Comparator.comparingInt((Cell c)->c.pos.getY()).thenComparingInt(c->c.pos.getZ()).thenComparingInt(c->c.pos.getX()));
                    for (var cell : cells) {
                        var block = new JsonObject();
                        var pos = new JsonArray();
                        pos.add(cell.pos.getX()-minX); pos.add(cell.pos.getY()-minY); pos.add(cell.pos.getZ()-minZ);
                        block.add("pos",pos);
                        block.addProperty("block",BuiltInRegistries.BLOCK.getKey(cell.state.getBlock()).toString());
                        if (!cell.state.getValues().isEmpty()) {
                            var properties = new JsonObject();
                            for (var entry : cell.state.getValues().entrySet()) {
                                properties.addProperty(entry.getKey().getName(),
                                        entry.getValue().toString().toLowerCase(java.util.Locale.ROOT));
                            }
                            block.add("properties",properties);
                        }
                        blocks.add(block);
                    }
                    json.add("blocks",blocks);
                    Path target=data.resolve(path + ".json");
                    Files.createDirectories(target.getParent());
                    Files.writeString(target,GSON.toJson(json) + "\n",StandardCharsets.UTF_8);
                    var row = new JsonObject();
                    row.addProperty("page",pageId); row.addProperty("title",title); row.addProperty("path",path);
                    row.addProperty("blocks",cells.size()); row.addProperty("size",width+"x"+height+"x"+depth);
                    row.addProperty("scene",sceneIndex); reportRows.add(row);
                    exported++;
                }
            } catch (RuntimeException | LinkageError | IOException ex) {
                failed++;
                var row = new JsonObject();
                row.addProperty("page",pageId); row.addProperty("error",ex.getClass().getSimpleName()+": "+ex.getMessage());
                reportRows.add(row);
            }
        }

        var report = new JsonObject();
        report.addProperty("guide",GUIDE_ID);
        report.addProperty("generated_at",Instant.now().toString());
        report.addProperty("min_blocks",minBlocks);
        report.addProperty("pages",pages);
        report.addProperty("scenes",scenes);
        report.addProperty("exported",exported);
        report.addProperty("small",small);
        report.addProperty("failed",failed);
        report.addProperty("too_large",tooLarge);
        report.add("entries",reportRows);
        Path reportPath=pack.resolve("capture-report.json");
        try {
            Files.writeString(reportPath,GSON.toJson(report)+"\n",StandardCharsets.UTF_8);
            var marker=new JsonObject();
            marker.addProperty("generated_by","multiblock_builder");
            marker.addProperty("capture",true);
            Files.writeString(pack.resolve(".mbuilder-capture"),GSON.toJson(marker)+"\n",StandardCharsets.UTF_8);
        } catch (IOException ex) {
            message(minecraft,"无法写入捕获报告: " + ex.getMessage());
        }
        message(minecraft,"指南捕获：页面 " + pages + "，场景 " + scenes + "，导出 " + exported
                + "，少于 " + minBlocks + " 方块 " + small + "，失败 " + failed + "，过大 " + tooLarge);
        reload(minecraft,pack);
        return new Result(pages,scenes,exported,small,failed,tooLarge,pack,reportPath);
    }

    private static Path resolvePack(Minecraft minecraft) {
        MinecraftServer server = minecraft.getSingleplayerServer();
        if (server != null) {
            Path datapacks = server.getWorldPath(LevelResource.DATAPACK_DIR);
            Path imported = datapacks.resolve("mbuilder_import");
            if (Files.isDirectory(imported)) return imported;
            return datapacks.resolve("mbuilder_capture");
        }
        return minecraft.gameDirectory.toPath().resolve("mbuilder-capture");
    }

    private static void reload(Minecraft minecraft,Path pack) {
        MinecraftServer server = minecraft.getSingleplayerServer();
        if (server == null) return;
        String id = "file/" + pack.getFileName();
        server.execute(() -> {
            var repository = server.getPackRepository();
            repository.reload();
            if (repository.getPack(id) != null) repository.addPack(id);
            server.reloadResources(repository.getSelectedIds());
        });
    }

    private static void collectStructureTags(Object node,List<MdxJsxFlowElement> out) {
        if (node instanceof MdxJsxFlowElement element && "StructureViewer".equals(element.name())) out.add(element);
        if (node instanceof MdAstParent<?> parent) {
            for (var child : parent.children()) collectStructureTags(child,out);
        }
    }

    private static GuidebookScene compileScene(PageCompiler compiler,MdxJsxFlowElement tag) {
        var sceneCompiler = new SceneTagCompiler();
        sceneCompiler.onExtensionsBuilt(compiler.getExtensions());
        var box = new LytVBox();
        sceneCompiler.compileBlockContext(compiler,box,tag);
        for (var child : box.getChildren()) {
            if (child instanceof LytGuidebookScene scene && scene.getScene()!=null) return scene.getScene();
        }
        return null;
    }

    private static String pageTitle(ParsedGuidePage parsed,String fallback) {
        try {
            var nav = parsed.getFrontmatter().navigationEntry();
            if (nav != null && nav.title()!=null && !nav.title().isBlank()) return nav.title();
        } catch (RuntimeException ignored) {}
        String path = parsed.getId().getPath();
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static String relativePath(ResourceLocation id) {
        String path = id.getPath();
        int index = path.indexOf("library/");
        if (index >= 0) path = path.substring(index + "library/".length());
        if (path.endsWith(".mdx")) path = path.substring(0,path.length()-4);
        else if (path.endsWith(".md")) path = path.substring(0,path.length()-3);
        return path;
    }

    private static void message(Minecraft minecraft,String text) {
        if (minecraft.player != null) minecraft.player.displayClientMessage(Component.literal(text),false);
    }

    private record Cell(BlockPos pos,BlockState state) {}

    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var stream = Files.walk(path)) {
            for (var p : stream.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
}
