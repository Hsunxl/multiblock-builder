package io.github.hsunxl.multiblockbuilder;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletionException;
import java.util.regex.Pattern;
import java.util.zip.*;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.loading.FMLPaths;

/** Server-side conversion of an installed structure-guide JAR into a world data pack. */
public final class GuideImport {
    private static final int MAX_NBT_BYTES = 8_000_000;
    private static final int MAX_JAR_BYTES = 100_000_000;
    private static final String MARKER = ".mbuilder-import";
    private static final String PACK_ID = "file/mbuilder_import";
    private static final Pattern TITLE = Pattern.compile("(?m)^\\s*title:\\s*(\"(?:[^\"\\\\]|\\\\.)*\")");
    private static final Pattern IMPORT_STRUCTURE = Pattern.compile("<ImportStructure\\s+[^>]*src=\"([^\"]+)\"");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private GuideImport() {}

    public record Result(Path jar, Path pack, int converted, int skipped, Path report) {}

    /** Imports from the best structure-guide JAR in the server's mods directory and enables the pack. */
    public static Result run(CommandSourceStack source, String include) throws IOException {
        var server = source.getServer();
        Path mods = FMLPaths.MODSDIR.get();
        Path jar = findGuideJar(mods);
        if (jar == null) throw new IOException("mods 中未找到包含 assets/*/structures/*.nbt 的图鉴 JAR");
        Path datapacks = server.getWorldPath(LevelResource.DATAPACK_DIR);
        Result result = importJar(jar,datapacks,"guide_import",include);

        var repository = server.getPackRepository();
        repository.reload();
        if (repository.getPack(PACK_ID) == null) {
            throw new IOException("数据包目录已生成，但服务器没有发现 " + PACK_ID + "；可执行 /datapack list 检查");
        }
        repository.addPack(PACK_ID);
        try {
            server.reloadResources(repository.getSelectedIds()).join();
        } catch (CompletionException ex) {
            throw new IOException("数据包重载失败",ex.getCause());
        }
        return result;
    }

    /** Finds the guide JAR with the most structure templates, preferring structure/guide-named files. */
    static Path findGuideJar(Path mods) throws IOException {
        if (!Files.isDirectory(mods)) return null;
        List<Path> jars;
        try (var stream = Files.list(mods)) {
            jars = stream.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted().toList();
        }
        Path best = null;
        int bestCount = 0;
        long bestModified = Long.MIN_VALUE;
        for (Path jar : jars) {
            String name = jar.getFileName().toString().toLowerCase(Locale.ROOT);
            if (!name.contains("structure") && !name.contains("guide") && !name.contains("atm")) continue;
            int count = countStructures(jar);
            long modified = Files.getLastModifiedTime(jar).toMillis();
            if (count > bestCount || (count == bestCount && count > 0 && modified > bestModified)) {
                best = jar; bestCount = count; bestModified = modified;
            }
        }
        if (best != null) return best;
        // Fallback for an unusual filename: inspect every JAR, but only if the cheap pass found nothing.
        for (Path jar : jars) {
            int count = countStructures(jar);
            long modified = Files.getLastModifiedTime(jar).toMillis();
            if (count > bestCount || (count == bestCount && count > 0 && modified > bestModified)) {
                best = jar; bestCount = count; bestModified = modified;
            }
        }
        return best;
    }

    private static int countStructures(Path jar) {
        try (var zip = new ZipFile(jar.toFile(),StandardCharsets.UTF_8)) {
            int count = 0;
            var entries = zip.entries();
            while (entries.hasMoreElements()) if (isStructureEntry(entries.nextElement().getName())) count++;
            return count;
        } catch (IOException | RuntimeException ex) {
            BuilderMod.LOG.debug("Skipping unreadable guide candidate {}",jar,ex);
            return 0;
        }
    }

    /** Pure conversion used by the command and tests. The output directory is replaced atomically. */
    static Result importJar(Path jar, Path datapacks, String namespace, String include) throws IOException {
        if (!namespace.matches("[a-z0-9_.-]+")) throw new IllegalArgumentException("非法命名空间: " + namespace);
        if (include == null || include.isBlank()) include = "*";
        if (include.length() > 200) throw new IllegalArgumentException("include 过长");
        byte[] jarBytes = Files.readAllBytes(jar);
        if (jarBytes.length > MAX_JAR_BYTES) throw new IOException("图鉴 JAR 超过 100 MB");
        String jarSha = sha256(jarBytes);
        Map<String,String> titles = readTitles(jar);

        Path temp = datapacks.resolve("mbuilder_import.tmp");
        deleteTree(temp);
        Files.createDirectories(temp.resolve("data").resolve(namespace).resolve("multiblock_blueprints"));

        var templates = new JsonArray();
        var skipped = new JsonArray();
        int converted = 0;
        try (var zip = new ZipFile(jar.toFile(),StandardCharsets.UTF_8)) {
            var list = new ArrayList<ZipEntry>();
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (isStructureEntry(entry.getName())) list.add(entry);
            }
            list.sort(Comparator.comparing(ZipEntry::getName));
            for (var entry : list) {
                String name = entry.getName();
                String relative = structurePath(name);
                if (relative == null) { skipped.add(skipped(name,"非法结构路径")); continue; }
                String slug = relative.endsWith(".nbt") ? relative.substring(0,relative.length()-4) : relative;
                if (!globMatches(include,slug)) continue;
                try {
                    if (!slug.matches("[a-z0-9_./-]+") || slug.contains(".."))
                        throw new IllegalArgumentException("非法模板路径");
                    if (entry.getSize() > MAX_NBT_BYTES) throw new IllegalArgumentException("NBT 超过 8 MB");
                    byte[] nbtBytes;
                    try (var in = zip.getInputStream(entry)) { nbtBytes = in.readNBytes(MAX_NBT_BYTES + 1); }
                    if (nbtBytes.length > MAX_NBT_BYTES) throw new IllegalArgumentException("NBT 超过 8 MB");
                    CompoundTag nbt = parseNbt(nbtBytes);
                    String title = titles.getOrDefault(name,slug.replace('_',' '));
                    Converted blueprint = convert(nbt,title);
                    Path target = temp.resolve("data").resolve(namespace).resolve("multiblock_blueprints")
                            .resolve(slug + ".json");
                    Files.createDirectories(target.getParent());
                    Files.writeString(target,GSON.toJson(blueprint.json()) + "\n",StandardCharsets.UTF_8);
                    var row = new JsonObject();
                    row.addProperty("id",namespace + ":" + slug);
                    row.addProperty("cells",blueprint.cells());
                    row.addProperty("stripped_block_entities",blueprint.stripped());
                    row.addProperty("source",name);
                    row.addProperty("sha256",sha256(nbtBytes));
                    templates.add(row);
                    converted++;
                } catch (RuntimeException | IOException ex) {
                    skipped.add(skipped(name,ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
                }
            }
        } catch (RuntimeException ex) {
            deleteTree(temp);
            throw ex;
        }

        if (converted == 0) {
            deleteTree(temp);
            throw new IOException("没有生成任何蓝图（跳过 " + skipped.size() + " 个）；未修改现有数据包");
        }

        var packMeta = new JsonObject();
        var pack = new JsonObject();
        pack.addProperty("pack_format",48);
        pack.addProperty("description","Multiblock Builder import: " + converted + " templates, creative-only");
        packMeta.add("pack",pack);
        Files.writeString(temp.resolve("pack.mcmeta"),GSON.toJson(packMeta) + "\n",StandardCharsets.UTF_8);

        var report = new JsonObject();
        report.addProperty("source_jar",jar.getFileName().toString());
        report.addProperty("source_jar_sha256",jarSha);
        report.addProperty("generated_at",Instant.now().toString());
        report.addProperty("namespace",namespace);
        report.addProperty("include",include);
        report.addProperty("converted",converted);
        report.addProperty("skipped",skipped.size());
        report.add("templates",templates);
        report.add("skipped_templates",skipped);
        Path reportPath = temp.resolve("import-report.json");
        Files.writeString(reportPath,GSON.toJson(report) + "\n",StandardCharsets.UTF_8);

        var marker = new JsonObject();
        marker.addProperty("generated_by",BuilderMod.ID);
        marker.addProperty("version",1);
        Files.writeString(temp.resolve(MARKER),GSON.toJson(marker) + "\n",StandardCharsets.UTF_8);

        Path target = datapacks.resolve("mbuilder_import");
        Path backup = datapacks.resolve("mbuilder_import.bak");
        if (Files.exists(target)) {
            if (!Files.exists(target.resolve(MARKER))) {
                deleteTree(temp);
                throw new IOException("目标数据包已存在且不是本工具生成的: " + target + "；请先移走它");
            }
            deleteTree(backup);
            move(target,backup);
        }
        try {
            move(temp,target);
        } catch (IOException ex) {
            if (Files.exists(backup) && !Files.exists(target)) move(backup,target);
            deleteTree(temp);
            throw ex;
        }
        deleteTree(backup);
        return new Result(jar,target,converted,skipped.size(),target.resolve("import-report.json"));
    }

    private static JsonObject skipped(String source, String reason) {
        var row = new JsonObject();
        row.addProperty("source",source);
        row.addProperty("reason",reason);
        return row;
    }

    static boolean isStructureEntry(String name) {
        return name.startsWith("assets/") && name.endsWith(".nbt") && name.contains("/structures/") && !name.contains("..");
    }

    static String structurePath(String name) {
        int index = name.indexOf("/structures/");
        if (index < 0) return null;
        return name.substring(index + "/structures/".length());
    }

    private static Map<String,String> readTitles(Path jar) throws IOException {
        Map<String,String> titles = new HashMap<>();
        try (var zip = new ZipFile(jar.toFile(),StandardCharsets.UTF_8)) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                String name = entry.getName();
                if (!name.startsWith("assets/") || !(name.endsWith(".md") || name.endsWith(".mdx"))) continue;
                if (entry.getSize() > 1_000_000) continue;
                String text;
                try (var in = zip.getInputStream(entry)) {
                    text = new String(in.readNBytes(1_000_001),StandardCharsets.UTF_8);
                }
                if (text.length() > 1_000_000) continue;
                var titleMatcher = TITLE.matcher(text);
                if (!titleMatcher.find()) continue;
                String title;
                try { title = JsonParser.parseString(titleMatcher.group(1)).getAsString(); }
                catch (RuntimeException ex) { continue; }
                String directory = name.substring(0,name.lastIndexOf('/'));
                var importMatcher = IMPORT_STRUCTURE.matcher(text);
                while (importMatcher.find()) {
                    String resolved = normalizePosix(directory + "/" + importMatcher.group(1));
                    if (resolved != null) titles.putIfAbsent(resolved,title);
                }
            }
        }
        return titles;
    }

    static String normalizePosix(String path) {
        Deque<String> parts = new ArrayDeque<>();
        for (String part : path.replace('\\','/').split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) {
                if (parts.isEmpty()) return null;
                parts.removeLast();
            } else parts.addLast(part);
        }
        return String.join("/",parts);
    }

    static boolean globMatches(String pattern, String value) {
        var regex = new StringBuilder("^");
        for (char c : pattern.toCharArray()) {
            if (c == '*') regex.append(".*");
            else if (c == '?') regex.append('.');
            else regex.append(Pattern.quote(String.valueOf(c)));
        }
        return Pattern.compile(regex.append('$').toString()).matcher(value).matches();
    }

    static CompoundTag parseNbt(byte[] bytes) throws IOException {
        if (bytes.length > MAX_NBT_BYTES) throw new IOException("NBT 超过 8 MB");
        boolean gzip = bytes.length >= 2 && (bytes[0] & 0xff) == 0x1f && (bytes[1] & 0xff) == 0x8b;
        try (var in = new ByteArrayInputStream(bytes)) {
            if (gzip) return NbtIo.readCompressed(in,NbtAccounter.create(MAX_NBT_BYTES));
            return NbtIo.read(new DataInputStream(in),NbtAccounter.create(MAX_NBT_BYTES));
        }
    }

    record Converted(JsonObject json, int cells, int stripped) {}

    static Converted convert(CompoundTag nbt, String title) {
        var size = nbt.getList("size",Tag.TAG_INT);
        if (size.size() != 3) throw new IllegalArgumentException("size 必须是 [x,y,z]");
        int width = size.getInt(0), height = size.getInt(1), depth = size.getInt(2);
        if (width < 1 || height < 1 || depth < 1 || width > 64 || height > 64 || depth > 64)
            throw new IllegalArgumentException("size 超出 1..64");
        var palette = nbt.getList("palette",Tag.TAG_COMPOUND);
        if (palette.isEmpty()) throw new IllegalArgumentException("缺少单一 palette");
        var blocks = nbt.getList("blocks",Tag.TAG_COMPOUND);
        if (blocks.isEmpty()) throw new IllegalArgumentException("缺少 blocks");

        var cells = new JsonArray();
        var seen = new HashSet<BlockPos>();
        int stripped = 0;
        for (int i=0;i<blocks.size();i++) {
            var block = blocks.getCompound(i);
            if (!block.contains("state",Tag.TAG_INT)) throw new IllegalArgumentException("缺少 state");
            int index = block.getInt("state");
            if (index < 0 || index >= palette.size()) throw new IllegalArgumentException("非法 palette 索引");
            var state = palette.getCompound(index);
            String name = state.getString("Name");
            if (name.isEmpty()) throw new IllegalArgumentException("palette 缺少 Name");
            if (name.equals("minecraft:air") || name.equals("minecraft:cave_air")
                    || name.equals("minecraft:void_air") || name.equals("minecraft:structure_void")) continue;
            if (!name.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) throw new IllegalArgumentException("非法方块 ID");
            var pos = block.getList("pos",Tag.TAG_INT);
            if (pos.size() != 3) throw new IllegalArgumentException("非法 pos");
            int x=pos.getInt(0), y=pos.getInt(1), z=pos.getInt(2);
            if (x < 0 || y < 0 || z < 0 || x >= width || y >= height || z >= depth)
                throw new IllegalArgumentException("pos 越界");
            var key = new BlockPos(x,y,z);
            if (!seen.add(key)) throw new IllegalArgumentException("重复坐标");
            var cell = new JsonObject();
            var jsonPos = new JsonArray(); jsonPos.add(x); jsonPos.add(y); jsonPos.add(z);
            cell.add("pos",jsonPos);
            cell.addProperty("block",name);
            if (state.contains("Properties",Tag.TAG_COMPOUND)) {
                var properties = state.getCompound("Properties");
                if (!properties.isEmpty()) {
                    var jsonProperties = new JsonObject();
                    for (String property : properties.getAllKeys()) {
                        if (!property.matches("[a-z0-9_]+")) throw new IllegalArgumentException("非法方块属性");
                        String value = properties.getString(property);
                        if (!value.matches("[a-z0-9_.-]+")) throw new IllegalArgumentException("非法方块属性值");
                        jsonProperties.addProperty(property,value);
                    }
                    cell.add("properties",jsonProperties);
                }
            }
            cells.add(cell);
            if (block.contains("nbt")) stripped++;
        }
        if (cells.isEmpty() || cells.size() > Blueprint.MAX_CELLS)
            throw new IllegalArgumentException("非空气方块数量超出 1..4096");
        if (title == null || title.isBlank()) title = "Imported structure";
        if (title.length() > 160) title = title.substring(0,160);
        var json = new JsonObject();
        json.addProperty("title",title);
        var jsonSize = new JsonArray(); jsonSize.add(width); jsonSize.add(height); jsonSize.add(depth);
        json.add("size",jsonSize);
        json.addProperty("survival",false);
        json.add("blocks",cells);
        return new Converted(json,cells.size(),stripped);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }

    private static void move(Path from, Path to) throws IOException {
        try { Files.move(from,to,StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException ex) { Files.move(from,to); }
    }

    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) return;
        Files.walkFileTree(path,new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException ex) throws IOException {
                if (ex != null) throw ex;
                Files.delete(dir); return FileVisitResult.CONTINUE;
            }
        });
    }
}
