package com.github.xandergos.terraindiffusionmc.client;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

import com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.PerformanceDiagnostics;
import com.github.xandergos.terraindiffusionmc.worldgen.preparation.FrameBudget;
import com.github.xandergos.terraindiffusionmc.worldgen.settings.WorldSession;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

public final class ClientPerformance {
    public static final FrameBudget FRAMES = new FrameBudget();
    private static long lastStart;
    private static volatile boolean paused, measuring;
    private static WorldSession observedTicket;
    private static Object observedWorld;

    private ClientPerformance() {}

    public static void frame(long start, long end) {
        if (!measuring) {
            lastStart = 0;
            return;
        }
        long previous = lastStart, gap = previous == 0 ? 0 : start - previous;
        lastStart = start;
        if (FRAMES.observe(gap, end - start))
            PerformanceDiagnostics.spike(previous == 0 ? start : previous, end, gap, end - start, FRAMES.lastSpike());
    }

    public static boolean mayPublish() {
        return measuring && !paused && FRAMES.mayPublish();
    }

    public static void install() {
        PerformanceDiagnostics.install(MinecraftClient.getInstance()
                .runDirectory
                .toPath()
                .resolve("logs/european-wilderness-performance.jsonl"));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> dispatcher.register(literal("td-perf")
                .then(literal("status").executes(c -> {
                    c.getSource().sendFeedback(Text.literal(FRAMES.statistics()));
                    c.getSource().sendFeedback(Text.literal(PerformanceDiagnostics.status()));
                    return 1;
                }))
                .then(literal("reset-frames").executes(c -> {
                    FRAMES.resetMeasurements();
                    c.getSource()
                            .sendFeedback(Text.literal("Выборка кадров очищена; управление нагрузкой не изменено"));
                    return 1;
                }))
                .then(literal("snapshot").executes(c -> {
                    var ticket = WorldSession.current();
                    boolean accepted = PerformanceDiagnostics.requestSnapshot(() -> {
                        var report = new java.util.LinkedHashMap<String, Object>();
                        report.put("frames", FRAMES.measurements());
                        report.put(
                                "frame_note",
                                "CPU frame gaps/render calls; recent window <=8192 events; generated display frames and GPU timings are excluded.");
                        report.put(
                                "session", ticket == null ? "none" : ticket.id().toString());
                        report.put("session_active", ticket != null && ticket.isActive());
                        report.put(
                                "inference",
                                com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider
                                        .inferenceSnapshot());
                        report.put(
                                "generation",
                                com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.GenerationMetrics
                                        .snapshot());
                        if (ticket != null && ticket.automaticWater() != null)
                            report.put("water", ticket.automaticWater().measurements());
                        return report;
                    });
                    c.getSource()
                            .sendFeedback(Text.literal(
                                    accepted
                                            ? "Снимок производительности заказан: logs/european-wilderness-performance.jsonl"
                                            : "Предыдущий снимок ещё ожидает записи"));
                    return accepted ? 1 : 0;
                }))
                .then(literal("recent").executes(c -> {
                    c.getSource().sendFeedback(Text.literal(PerformanceDiagnostics.recent()));
                    return 1;
                }))));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            boolean nextPaused = client.isPaused();
            var ticket = WorldSession.current();
            // frame pacing gates publication while the game is running.
            boolean active = !nextPaused && client.world != null && ticket != null && ticket.isActive();
            if (ticket != observedTicket
                    || client.world != observedWorld
                    || paused != nextPaused
                    || measuring != active) {
                FRAMES.reset();
                lastStart = 0;
                observedTicket = ticket;
                observedWorld = client.world;
                PerformanceDiagnostics.active(false);
                PerformanceDiagnostics.active(active);
            }
            paused = nextPaused;
            measuring = active;
            if (ticket != null && ticket.isActive())
                ticket.attachBackgroundPolicy(
                        () -> !paused && FRAMES.mayPrepare(), () -> !paused && FRAMES.claimPreparation());
        });
    }

    /** reports the reason coarse publication is paused. */
    public static String publishGateReason() {
        var client = MinecraftClient.getInstance();
        var ticket = WorldSession.current();
        if (client.world == null) return "NO_WORLD";
        if (ticket == null || !ticket.isActive()) return "SESSION_INACTIVE";
        if (paused) return "CLIENT_PAUSED";
        String frame = FRAMES.heartbeatState();
        if (!frame.equals("fresh")) return "FRAME_HEARTBEAT_" + frame;
        if (!FRAMES.mayPublish()) return "FRAME_BUDGET_PRESSURE";
        return "open";
    }
}
