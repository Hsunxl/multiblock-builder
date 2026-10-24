package io.github.hsunxl.multiblockbuilder.client;

import com.google.gson.JsonArray;
import io.github.hsunxl.multiblockbuilder.BuilderMod;
import java.util.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** A compact, searchable catalog with item icons and blueprint metadata. */
public final class CatalogScreen extends Screen {
    private static final int ROW_HEIGHT = 40;
    private static final int ROW_GAP = 6;
    private static final int PANEL_MAX = 620;
    // Vanilla widgets and item icons draw above z=0, regardless of call order.
    private static final int HOVER_CARD_Z = 400;

    private record Entry(String id,String title,boolean survival,boolean formation,
                         int width,int height,int depth,int cells,ItemStack icon,String category) {}

    private final List<Entry> entries = new ArrayList<>();
    private final List<AbstractWidget> dynamic = new ArrayList<>();
    private String query = "";
    private int page = 0;
    private int panelX, panelY, panelWidth, panelHeight, listTop, listBottom, footerY;
    private EditBox search;

    public CatalogScreen(JsonArray data) {
        super(Component.translatable("multiblock_builder.catalog"));
        for (var element : data) {
            var object = element.getAsJsonObject();
            String id = object.get("id").getAsString();
            String iconId = object.has("icon") ? object.get("icon").getAsString() : "minecraft:book";
            ItemStack icon = new ItemStack(Items.BOOK);
            try {
                var key = ResourceLocation.tryParse(iconId);
                if (key != null && BuiltInRegistries.ITEM.containsKey(key)) {
                    icon = new ItemStack(BuiltInRegistries.ITEM.get(key));
                }
            } catch (RuntimeException ex) {
                BuilderMod.LOG.debug("Invalid catalog icon {}",iconId,ex);
            }
            int width = 0, height = 0, depth = 0;
            if (object.has("size")) {
                var size = object.getAsJsonArray("size");
                width = size.get(0).getAsInt();
                height = size.get(1).getAsInt();
                depth = size.get(2).getAsInt();
            }
            String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
            int slash = path.lastIndexOf('/');
            String category = slash > 0 ? path.substring(0,slash) : "root";
            entries.add(new Entry(id,object.get("title").getAsString(),
                    object.has("survival") && object.get("survival").getAsBoolean(),
                    object.has("formation") && object.get("formation").getAsBoolean(),
                    width,height,depth,object.has("cells") ? object.get("cells").getAsInt() : 0,
                    icon,category));
        }
    }

    /** Shows a readable series name instead of a raw data-path slug when one is translated. */
    private String categoryLabel(String category) {
        String key = "multiblock_builder.category." + category;
        var language = net.minecraft.locale.Language.getInstance();
        return language.has(key) ? Component.translatable(key).getString() : category;
    }

    private int pageSize() {
        return Math.max(1,(listBottom - listTop) / (ROW_HEIGHT + ROW_GAP));
    }

    private List<Entry> filtered() {
        String q = query.toLowerCase(Locale.ROOT).trim();
        if (q.isEmpty()) return entries;
        String survivalWord = Component.translatable("multiblock_builder.badge.survival").getString().toLowerCase(Locale.ROOT);
        String creativeWord = Component.translatable("multiblock_builder.badge.creative").getString().toLowerCase(Locale.ROOT);
        return entries.stream().filter(e -> {
            String haystack = e.title + " " + e.id + " " + e.category + " " + categoryLabel(e.category)
                    + " " + (e.survival ? survivalWord : creativeWord);
            return haystack.toLowerCase(Locale.ROOT).contains(q);
        }).toList();
    }

    @Override protected void init() {
        panelWidth = Math.min(width - 28,PANEL_MAX);
        panelX = (width - panelWidth) / 2;
        panelY = 22;
        panelHeight = height - 44;
        listTop = panelY + 82;
        footerY = panelY + panelHeight - 42;
        listBottom = footerY - 8;

        search = new EditBox(font,panelX + 16,panelY + 46,panelWidth - 32,20,
                Component.translatable("multiblock_builder.search"));
        search.setHint(Component.translatable("multiblock_builder.search_hint"));
        search.setValue(query);
        search.setResponder(value -> { query = value; page = 0; refreshRows(); });
        addRenderableWidget(search);
        setInitialFocus(search);
        refreshRows();
    }

    private Button button(String text,int x,int y,int w,Runnable action) {
        var button = Button.builder(Component.literal(text),ignored -> action.run())
                .bounds(x,y,w,20).build();
        dynamic.add(button);
        addRenderableWidget(button);
        return button;
    }

    private void command(String command) {
        if (minecraft != null && minecraft.getConnection() != null) {
            minecraft.getConnection().sendCommand(command);
        }
    }

    private void refreshRows() {
        for (var widget : dynamic) removeWidget(widget);
        dynamic.clear();
        var list = filtered();
        int size = pageSize();
        int lastPage = Math.max(0,(list.size() - 1) / size);
        page = Math.max(0,Math.min(page,lastPage));

        for (int i = page * size; i < Math.min(list.size(),(page + 1) * size); i++) {
            var row = new Row(list.get(i),panelX + 16,listTop + (i - page * size) * (ROW_HEIGHT + ROW_GAP),
                    panelWidth - 32,ROW_HEIGHT);
            dynamic.add(row);
            addRenderableWidget(row);
        }

        int previousY = footerY;
        var previous = button("<",panelX + 16,previousY,32,()->{ page--; refreshRows(); });
        previous.active = page > 0;
        button((page + 1) + " / " + Math.max(1,lastPage + 1),panelX + 52,previousY,82,()->{});
        var next = button(">",panelX + 138,previousY,32,()->{ page++; refreshRows(); });
        next.active = page < lastPage;

        if (panelWidth >= 500) {
            button(Component.translatable("multiblock_builder.rotate").getString(),
                    panelX + 196,previousY,86,()->command("mbuilder rotate"));
            button(Component.translatable("multiblock_builder.materials").getString(),
                    panelX + 288,previousY,100,()->{ command("mbuilder materials"); onClose(); });
        }
        button(Component.translatable("gui.done").getString(),
                panelX + panelWidth - 84,previousY,68,this::onClose);
    }

    @Override public void renderBackground(GuiGraphics graphics,int mouseX,int mouseY,float partialTick) {
        super.renderBackground(graphics,mouseX,mouseY,partialTick);
        graphics.fill(panelX,panelY,panelX + panelWidth,panelY + panelHeight,0xF2101418);
        graphics.fill(panelX,panelY,panelX + panelWidth,panelY + 2,0xFF55D6FF);
        graphics.fill(panelX,panelY + panelHeight - 1,panelX + panelWidth,panelY + panelHeight,0xFF2B3A45);
        graphics.fill(panelX,panelY,panelX + 1,panelY + panelHeight,0xFF2B3A45);
        graphics.fill(panelX + panelWidth - 1,panelY,panelX + panelWidth,panelY + panelHeight,0xFF2B3A45);
        graphics.fill(panelX + 1,panelY + 39,panelX + panelWidth - 1,panelY + 40,0xFF2B3A45);
    }

    @Override public void render(GuiGraphics graphics,int mouseX,int mouseY,float partialTick) {
        super.render(graphics,mouseX,mouseY,partialTick);
        graphics.drawString(font,title,panelX + 16,panelY + 12,0xFFF2F6FA,false);
        graphics.drawString(font,Component.translatable("multiblock_builder.catalog.subtitle",filtered().size()),
                panelX + 16,panelY + 26,0xFF8FA3B0,false);
        if (filtered().isEmpty()) {
            graphics.drawCenteredString(font,Component.translatable("multiblock_builder.catalog.empty"),
                    panelX + panelWidth / 2,panelY + panelHeight / 2,0xFF8FA3B0);
        }
        renderHoverCard(graphics,mouseX,mouseY);
    }

    private Entry hoveredEntry(int mouseX,int mouseY) {
        for (var widget : dynamic) {
            if (widget instanceof Row row && row.isMouseOver(mouseX,mouseY)) return row.entry;
        }
        return null;
    }

    /** A large 3D thumbnail instead of a raw registry id. */
    private void renderHoverCard(GuiGraphics graphics,int mouseX,int mouseY) {
        Entry entry = hoveredEntry(mouseX,mouseY);
        if (entry == null) return;

        int cardWidth = 252, cardHeight = 192;
        int x = mouseX + 16, y = mouseY - 24;
        if (x + cardWidth > width - 4) x = mouseX - cardWidth - 16;
        x = Math.max(4,Math.min(x,width - cardWidth - 4));
        y = Math.max(4,Math.min(y,height - cardHeight - 4));

        // Finish the underlying widgets before entering an isolated tooltip layer.
        graphics.flush();
        graphics.pose().pushPose();
        try {
            graphics.pose().translate(0,0,HOVER_CARD_Z);
            drawHoverCard(graphics,entry,x,y,cardWidth,cardHeight);
        } finally {
            graphics.flush();
            graphics.pose().popPose();
        }
    }

    private void drawHoverCard(GuiGraphics graphics,Entry entry,int x,int y,int cardWidth,int cardHeight) {
        graphics.fill(x,y,x + cardWidth,y + cardHeight,0xFF0C1218);
        graphics.fill(x,y,x + cardWidth,y + 2,0xFF55D6FF);
        graphics.fill(x,y + cardHeight - 1,x + cardWidth,y + cardHeight,0xFF2B3A45);
        graphics.fill(x,y,x + 1,y + cardHeight,0xFF2B3A45);
        graphics.fill(x + cardWidth - 1,y,x + cardWidth,y + cardHeight,0xFF2B3A45);

        int previewX = x + 10, previewY = y + 10, previewW = cardWidth - 20, previewH = 110;
        graphics.fill(previewX,previewY,previewX + previewW,previewY + previewH,0xFF070C10);
        graphics.fill(previewX,previewY,previewX + previewW,previewY + 1,0x33FFFFFF);
        // The voxel texture uses a different render type; submit its backdrop first.
        graphics.flush();
        VoxelPreview preview = BuilderClient.thumbnail(entry.id);
        if (preview != null) {
            preview.render(graphics,previewX,previewY,previewW,previewH);
        } else {
            graphics.drawCenteredString(font,Component.translatable("multiblock_builder.preview.loading"),
                    previewX + previewW / 2,previewY + previewH / 2 - 4,0xFF8FA3B0);
        }

        int textX = x + 10;
        graphics.drawString(font,font.plainSubstrByWidth(entry.title,cardWidth - 20),
                textX,y + 126,0xFFF2F6FA,false);

        String meta = entry.width + "×" + entry.height + "×" + entry.depth + " · " + entry.cells
                + " " + Component.translatable("multiblock_builder.blocks").getString();
        graphics.drawString(font,font.plainSubstrByWidth(meta,cardWidth - 20 - 52),
                textX,y + 141,0xFFA9BCC8,false);

        String badge = entry.survival
                ? Component.translatable("multiblock_builder.badge.survival").getString()
                : Component.translatable("multiblock_builder.badge.creative").getString();
        int badgeWidth = font.width(badge) + 10;
        graphics.fill(x + cardWidth - badgeWidth - 10,y + 138,x + cardWidth - 10,y + 152,
                entry.survival ? 0x3322C7AA : 0x33FFB74D);
        graphics.drawString(font,badge,x + cardWidth - badgeWidth - 5,y + 141,
                entry.survival ? 0xFF7CF0D2 : 0xFFFFD18A,false);

        String series = Component.translatable("multiblock_builder.card.series",categoryLabel(entry.category)).getString();
        if (entry.formation) series += " · " + Component.translatable("multiblock_builder.badge.auto").getString();
        graphics.drawString(font,font.plainSubstrByWidth(series,cardWidth - 20),
                textX,y + 156,0xFF7E929E,false);

        String hint = entry.survival
                ? Component.translatable("multiblock_builder.card.hint").getString()
                : Component.translatable("multiblock_builder.card.creative_hint").getString();
        graphics.drawString(font,font.plainSubstrByWidth(hint,cardWidth - 20),
                textX,y + 173,entry.survival ? 0xFF6E8492 : 0xFFD9A05B,false);
    }

    @Override public boolean mouseScrolled(double mouseX,double mouseY,double horizontal,double vertical) {
        if (vertical != 0 && mouseX >= panelX && mouseX <= panelX + panelWidth
                && mouseY >= listTop && mouseY <= listBottom) {
            page += vertical > 0 ? -1 : 1;
            refreshRows();
            return true;
        }
        return super.mouseScrolled(mouseX,mouseY,horizontal,vertical);
    }

    @Override public boolean isPauseScreen() { return false; }

    private final class Row extends AbstractWidget {
        private final Entry entry;

        private Row(Entry entry,int x,int y,int width,int height) {
            super(x,y,width,height,Component.literal(entry.title));
            this.entry = entry;
        }

        @Override protected void renderWidget(GuiGraphics graphics,int mouseX,int mouseY,float partialTick) {
            int x = getX(), y = getY(), w = getWidth(), h = getHeight();
            graphics.fill(x,y,x + w,y + h,isHovered ? 0xFF1D2A33 : 0xFF141B21);
            graphics.fill(x,y,x + 3,y + h,entry.survival ? 0xFF55D6FF : 0xFFFFB74D);
            graphics.renderItem(entry.icon,x + 9,y + 10);

            String badge = entry.survival
                    ? Component.translatable("multiblock_builder.badge.survival").getString()
                    : Component.translatable("multiblock_builder.badge.creative").getString();
            int badgeWidth = font.width(badge) + 10;
            int badgeX = x + w - badgeWidth - 8;
            int titleWidth = Math.max(20,badgeX - (x + 38) - 4);
            graphics.drawString(font,font.plainSubstrByWidth(entry.title,titleWidth),
                    x + 36,y + 7,0xFFF2F6FA,false);

            String meta = categoryLabel(entry.category) + " · " + entry.width + "×" + entry.height + "×" + entry.depth
                    + " · " + entry.cells + " " + Component.translatable("multiblock_builder.blocks").getString();
            if (entry.formation) meta += " · " + Component.translatable("multiblock_builder.badge.auto").getString();
            graphics.drawString(font,font.plainSubstrByWidth(meta,Math.max(20,badgeX - (x + 36) - 4)),
                    x + 36,y + 22,0xFF8FA3B0,false);

            graphics.fill(badgeX,y + 8,badgeX + badgeWidth,y + 22,
                    entry.survival ? 0x3322C7AA : 0x33FFB74D);
            graphics.drawString(font,badge,badgeX + 5,y + 11,
                    entry.survival ? 0xFF7CF0D2 : 0xFFFFD18A,false);
        }

        @Override public void onClick(double mouseX,double mouseY,int button) {
            if (button == 0) {
                command("mbuilder select " + entry.id);
                onClose();
            }
        }

        @Override protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }
}
