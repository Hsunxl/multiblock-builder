package io.github.hsunxl.multiblockbuilder.client;

import com.google.gson.JsonArray;
import java.util.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class CatalogScreen extends Screen {
    private record Entry(String id,String title,boolean survival) {}
    private final List<Entry> entries;
    private String query=""; private int page=0;
    public CatalogScreen(JsonArray data) {
        super(Component.translatable("multiblock_builder.catalog"));
        entries=new ArrayList<>();
        for(var el:data) {var o=el.getAsJsonObject(); entries.add(new Entry(o.get("id").getAsString(),o.get("title").getAsString(),o.get("survival").getAsBoolean()));}
    }
    private int pageSize() { return Math.max(1,Math.min(12,(height-145)/24)); }
    private List<Entry> filtered() { String q=query.toLowerCase(Locale.ROOT); return entries.stream().filter(e->(e.title+" "+e.id).toLowerCase(Locale.ROOT).contains(q)).toList(); }
    @Override protected void init() {
        var search=new EditBox(font,width/2-150,30,300,20,Component.translatable("multiblock_builder.search"));
        search.setValue(query); search.setResponder(s->{query=s;page=0; refreshRows();}); addRenderableWidget(search); setInitialFocus(search);
        refreshRows();
    }
    private final List<Button> rows=new ArrayList<>();
    private void button(String text,int x,int y,int w,Runnable action) {
        var b=Button.builder(Component.literal(text),ignored->action.run()).bounds(x,y,w,20).build(); rows.add(b); addRenderableWidget(b);
    }
    private void command(String command) { if(minecraft!=null&&minecraft.getConnection()!=null) minecraft.getConnection().sendCommand(command); }
    private void refreshRows() {
        for(var b:rows) removeWidget(b); rows.clear();
        var list=filtered(); int n=pageSize(); page=Math.max(0,Math.min(page,Math.max(0,(list.size()-1)/n)));
        for(int i=page*n;i<Math.min(list.size(),(page+1)*n);i++) {
            var e=list.get(i); String label=e.title+(e.survival?"":" [Creative]");
            button(font.plainSubstrByWidth(label,280),width/2-150,58+(i-page*n)*24,300,()->{command("mbuilder select "+e.id); onClose();});
        }
        int y=height-78;
        button("<",width/2-150,y,40,()->{page--;refreshRows();});
        button((page+1)+" / "+Math.max(1,(list.size()+n-1)/n),width/2-105,y,210,()->{});
        button(">",width/2+110,y,40,()->{page++;refreshRows();});
        button(Component.translatable("multiblock_builder.rotate").getString(),width/2-150,height-52,95,()->command("mbuilder rotate"));
        button(Component.translatable("multiblock_builder.materials").getString(),width/2-48,height-52,96,()->{command("mbuilder materials");onClose();});
        button(Component.translatable("gui.done").getString(),width/2+55,height-52,95,this::onClose);
    }
    @Override public void render(GuiGraphics g,int x,int y,float tick) { super.render(g,x,y,tick); g.drawCenteredString(font,title,width/2,12,0xffffff); }
    @Override public boolean isPauseScreen() { return false; }
}
