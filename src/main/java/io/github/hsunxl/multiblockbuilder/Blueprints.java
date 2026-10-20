package io.github.hsunxl.multiblockbuilder;

import com.google.gson.*;
import java.util.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.*;
import net.minecraft.util.profiling.ProfilerFiller;

public final class Blueprints extends SimpleJsonResourceReloadListener {
    public static final Blueprints INSTANCE = new Blueprints();
    private volatile Map<ResourceLocation, Blueprint> values = Map.of();
    private Blueprints() { super(new Gson(), "multiblock_blueprints"); }
    @Override protected void apply(Map<ResourceLocation, JsonElement> data, ResourceManager resources, ProfilerFiller profiler) {
        Map<ResourceLocation, Blueprint> next = new TreeMap<>();
        data.forEach((id,json) -> {
            try { next.put(id, Blueprint.parse(id,json.getAsJsonObject())); }
            catch (RuntimeException ex) { BuilderMod.LOG.warn("Skipped blueprint {}: {}", id, ex.getMessage()); }
        });
        values = Collections.unmodifiableMap(next);
        long survival = values.values().stream().filter(Blueprint::survival).count();
        BuilderMod.LOG.info("Loaded {} buildable blueprints ({} survival, {} creative-only)",
                values.size(), survival, values.size() - survival);
    }
    public Blueprint get(String id) { var key=ResourceLocation.tryParse(id); return key==null?null:values.get(key); }
    public Collection<Blueprint> all() { return values.values(); }
}
