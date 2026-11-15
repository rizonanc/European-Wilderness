package com.github.xandergos.terraindiffusionmc.client;

import com.github.xandergos.terraindiffusionmc.TerrainDiffusionMc;
import com.github.xandergos.terraindiffusionmc.compat.voxy.VegetationMipRebuild;
import com.github.xandergos.terraindiffusionmc.compat.voxy.VoxyCompat;
import com.github.xandergos.terraindiffusionmc.compat.voxy.WaterMipRebuild;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import com.github.xandergos.terraindiffusionmc.worldgen.settings.WorldSession;
import com.github.xandergos.terraindiffusionmc.worldgen.preparation.PreparationProgress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.Selectable;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ElementListWidget;
import net.minecraft.client.gui.widget.TextWidget;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.world.World;

final class SettingsStatusScreen extends Screen {
    private static final String PREFIX = "terrain-diffusion-mc.settings.status.";
    private final Screen parent;
    private ReportList reportList;
    private TextWidget feedback;
    private ButtonWidget water, stopWater, vegetation, stopVegetation, cancelPrepare;
    private WorldSession reportedSession;
    private List<String> stats = List.of();
    private List<Text> report = List.of();
    private CompletableFuture<List<String>> pending;
    private WorldSession pendingSession;
    private long nextRefresh;

    SettingsStatusScreen(Screen parent) {
        super(label("title"));
        this.parent = parent;
    }

    private static Text label(String key) {
        return Text.translatable(PREFIX + key);
    }

    @Override
    protected void init() {
        int center = width / 2;
        addDrawableChild(new TextWidget(0, 10, width, 12, title, textRenderer));
        water = action("water", center - 150, 28, 146, () -> message(VoxyCompat.rebuildWater(16)));
        vegetation = action("vegetation", center + 4, 28, 146, () -> message(VoxyCompat.rebuildVegetation(32)));
        stopWater = action("stop_water", center - 150, 51, 146, () -> message(WaterMipRebuild.stop()));
        stopVegetation = action("stop_vegetation", center + 4, 51, 146, () -> message(VegetationMipRebuild.stop()));
        reportList = addDrawableChild(new ReportList(Math.max(22, height - 141)));
        feedback = addDrawableChild(new TextWidget(0, height - 55, width, 10, Text.empty(), textRenderer));
        cancelPrepare =
                action(
                        "cancel_prepare",
                        center - 150,
                        height - 36,
                        103,
                        () -> {
                            var session = localSession();
                            if (session != null && session.preparation() != null) session.preparation().cancel();
                        });
        action(
                "copy",
                center - 42,
                height - 36,
                100,
                () -> {
                    client.keyboard.setClipboard(String.join("\n", report.stream().map(Text::getString).toList()));
                    feedback.setMessage(label("copied"));
                });
        addDrawableChild(
                ButtonWidget.builder(Text.translatable("gui.back"), button -> close())
                        .dimensions(center + 64, height - 36, 86, 20)
                        .build());
        report = List.of();
        nextRefresh = 0;
        tick();
    }

    private ButtonWidget action(String key, int x, int y, int width, Runnable action) {
        return addDrawableChild(
                ButtonWidget.builder(label(key), button -> action.run())
                        .dimensions(x, y, width, 20)
                        .tooltip(Tooltip.of(label(key + "_tip")))
                        .build());
    }

    private void message(String message) {
        var full = Text.literal(message);
        feedback.setMessage(Text.literal(textRenderer.trimToWidth(message, width - 12)));
        feedback.setTooltip(Tooltip.of(full));
        nextRefresh = 0;
    }

    private WorldSession localSession() {
        var server = client.getServer();
        var overworld = server == null ? null : server.getOverworld();
        var session = WorldSession.current();
        return session != null
                        && session.isActive()
                        && overworld != null
                        && overworld.getChunkManager().getChunkGenerator().getBiomeSource()
                                instanceof TerrainDiffusionBiomeSource
                ? session
                : null;
    }

    @Override
    public void tick() {
        WorldSession session = localSession();
        boolean canRebuild =
                session != null
                        && client.world != null
                        && client.world.getRegistryKey().equals(World.OVERWORLD)
                        && VoxyCompat.usable();
        water.active = canRebuild && !WaterMipRebuild.running();
        vegetation.active = canRebuild && !VegetationMipRebuild.running();
        stopWater.active = WaterMipRebuild.running();
        stopVegetation.active = VegetationMipRebuild.running();
        var prep = session == null ? null : session.preparation();
        cancelPrepare.active = prep != null && prep.progress().state() == PreparationProgress.State.RUNNING;

        if (reportedSession != session) {
            reportedSession = session;
            stats = List.of();
            nextRefresh = 0;
        }
        if (pending != null && pending.isDone()) {
            if (session == pendingSession) stats = pending.getNow(List.of());
            pending = null;
            updateReport(session);
        }
        long now = System.nanoTime();
        if (nextRefresh != 0 && now - nextRefresh < 0) return;
        nextRefresh = now + 1_000_000_000L;
        if (session != null && pending == null) {
            pendingSession = session;
            pending =
                    CompletableFuture.supplyAsync(() -> TerrainDiffusionMc.statsReport(session))
                            .exceptionally(
                                    failure -> List.of("Terrain statistics: " + failure.getClass().getSimpleName()));
        }
        updateReport(session);
    }

    private void updateReport(WorldSession session) {
        var lines = new ArrayList<Text>();
        if (session == null) {
            lines.add(label("no_world"));
        } else {
            var profile = session.profile();
            var waterOptions = profile.water();
            lines.add(
                    Text.translatable(
                            PREFIX + "world_profile",
                            profile.worldScale(),
                            profile.mountainRelief(),
                            profile.biomeVariety(),
                            waterOptions.widthVariation(),
                            waterOptions.depthVariation(),
                            waterOptions.lakeThreshold()));
            var prep = session.preparation();
            lines.add(prep == null ? label("no_preparation") : Text.literal(prep.progress().toString()));
            var automatic = session.automaticWater();
            var prepared = session.preparedWater();
            if (automatic != null) lines.add(Text.literal(automatic.status()));
            else if (prepared != null) lines.add(Text.literal(prepared.status()));
        }
        lines.add(Text.literal(VoxyCompat.status()));
        lines.add(Text.literal(WaterMipRebuild.status()));
        lines.add(Text.literal(VegetationMipRebuild.status()));
        for (String stat : stats) lines.add(Text.literal(stat));
        if (lines.equals(report)) return;
        report = List.copyOf(lines);
        var rows = new ArrayList<ReportRow>();
        for (Text line : report) {
            for (OrderedText wrapped : textRenderer.wrapLines(line, reportList.getRowWidth() - 8))
                rows.add(new ReportRow(wrapped));
            rows.add(new ReportRow(OrderedText.EMPTY));
        }
        double scroll = reportList.getScrollY();
        reportList.replaceEntries(rows);
        reportList.setScrollY(scroll);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void close() {
        client.setScreen(parent);
    }

    private final class ReportList extends ElementListWidget<ReportRow> {
        ReportList(int height) {
            super(SettingsStatusScreen.this.client, SettingsStatusScreen.this.width, height, 77, 12);
            centerListVertically = false;
        }

        @Override
        public int getRowWidth() {
            return SettingsStatusScreen.this.width - 38;
        }
    }

    private final class ReportRow extends ElementListWidget.Entry<ReportRow> {
        private final OrderedText line;

        ReportRow(OrderedText line) {
            this.line = line;
        }

        @Override
        public List<? extends Element> children() {
            return List.of();
        }

        @Override
        public List<? extends Selectable> selectableChildren() {
            return List.of();
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, boolean hovered, float delta) {
            context.drawTextWithShadow(textRenderer, line, getContentX(), getContentY(), 0xFFE4E8ED);
        }
    }
}
