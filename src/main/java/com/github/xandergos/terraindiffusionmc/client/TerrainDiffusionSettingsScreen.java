package com.github.xandergos.terraindiffusionmc.client;

import com.github.xandergos.terraindiffusionmc.compat.roadweaver.RoadWeaverTerrain;
import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfigEditor;
import com.github.xandergos.terraindiffusionmc.config.TerrainSettingsCatalog;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import com.github.xandergos.terraindiffusionmc.worldgen.settings.WorldSession;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import com.github.xandergos.terraindiffusionmc.config.SettingsDraft;
import com.github.xandergos.terraindiffusionmc.config.SettingsPreferences;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.screen.narration.NarrationPart;
import net.minecraft.client.gui.cursor.StandardCursors;
import net.minecraft.client.gui.Click;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.Selectable;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.ElementListWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.widget.TextWidget;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.util.math.ColorHelper;

@Environment(EnvType.CLIENT)
public final class TerrainDiffusionSettingsScreen extends Screen {
    private static final String TRANSLATION = "terrain-diffusion-mc.settings.";
    private static final Identifier BACKGROUND =
            Identifier.of("terrain-diffusion-mc", "textures/gui/settings-landscape.png");
    private static final int AMBER = 0xFFF3BC53;
    private static final int SIDEBAR = 0xC012191F;
    private static final int PANE = 0x78101820;
    private static final int LINE = 0x684D5865;
    private static final int LIGHT = 0xFFF0F1F2;
    private static final int MUTED = 0xFFC6C8CA;

    private static final List<String> WORLD_SECTIONS = List.of("terrain", "rivers", "nature", "animals");

    private enum View {
        SECTION,
        CHANGED,
        FAVORITES,
        RECENT
    }

    private final Screen parent;
    private final Path configPath;
    private final SettingsDraft draft;
    private final SettingsPreferences preferences;
    private int page = TerrainSettingsCatalog.pageFor("terrain.mountain_relief");
    private View view = View.SECTION;
    private String query = "";
    private Text message = Text.empty();
    private TextWidget errorWidget;
    private SettingsList settingsList;
    private MenuButton undoButton;
    private MenuButton resetButton;
    private MenuButton changedButton;
    private SectionsList sectionsList;
    private double sectionsScroll;
    private int frameLeft, frameTop, frameRight, frameBottom;
    private int sidebarWidth;
    private int paneX;
    private int contentTop;
    private boolean refreshPending;
    private long openingStartedAt = -1;
    private long sectionChangedAt;
    private long lastFrameAt;
    private float sectionTransition;
    private float hoverStep;

    public TerrainDiffusionSettingsScreen(Screen parent) {
        super(label("title"));
        this.parent = parent;
        configPath = TerrainDiffusionConfig.configPath();
        SettingsDraft loaded = null;
        SettingsPreferences stored = null;
        try {
            loaded =
                    new SettingsDraft(
                            TerrainDiffusionConfigEditor.load(configPath), TerrainDiffusionConfigEditor.loadDefaults());
        } catch (IOException failure) {
            message = label("load_error", failure.getMessage()).formatted(Formatting.RED);
        }
        try {
            stored = SettingsPreferences.load(TerrainDiffusionConfig.menuPath());
        } catch (IOException failure) {
            if (loaded != null) message = label("preferences_error").formatted(Formatting.RED);
        }
        draft = loaded;
        preferences = stored;
    }

    private static MutableText label(String key, Object... args) {
        return Text.translatable(TRANSLATION + key, args);
    }

    @Override
    protected void init() {
        if (sectionsList != null) sectionsScroll = sectionsList.getScrollY();
        int frameWidth = Math.min(width - 24, 780);
        int frameHeight = Math.min(height - 24, 450);
        frameLeft = (width - frameWidth) / 2;
        frameTop = (height - frameHeight) / 2;
        frameRight = frameLeft + frameWidth;
        frameBottom = frameTop + frameHeight;
        sidebarWidth = Math.clamp(frameWidth / 4, 100, 150);
        paneX = frameLeft + sidebarWidth + 5;
        int paneWidth = frameRight - paneX;
        int left = paneX + 8;
        int toolbarWidth = paneWidth - 16;
        boolean wide = toolbarWidth >= 420;
        int searchWidth = wide ? toolbarWidth / 3 : toolbarWidth;
        int filterX = wide ? left + searchWidth + 5 : left;
        int filterWidth = (wide ? toolbarWidth - searchWidth - 5 : toolbarWidth) / 3 - 2;
        int filterY = frameTop + (wide ? 65 : 88);
        contentTop = filterY + 25;
        menuButton(Text.literal("×"), frameRight - 24, frameTop + 3, 20, this::close, false)
                .setTooltip(Tooltip.of(Text.translatable("gui.cancel")));
        int sideLeft = frameLeft + 6;
        int sideButtonWidth = sidebarWidth - 10;
        menuButton(Text.translatable("gui.done"), sideLeft, frameBottom - 26, sideButtonWidth, this::save, true)
                        .active =
                draft != null;
        sectionsList = addDrawableChild(new SectionsList(Math.max(22, frameHeight - 163)));
        sectionsList.setScrollY(sectionsScroll);
        menuButton(label("profiles"), sideLeft, frameBottom - 74, sideButtonWidth, this::openProfiles, false).active =
                draft != null;
        menuButton(
                label("status.title"),
                sideLeft,
                frameBottom - 50,
                sideButtonWidth,
                () -> client.setScreen(new SettingsStatusScreen(this)),
                false);
        addSearch(left, frameTop + 65, searchWidth);
        addFilters(filterX, filterY, filterWidth);
        settingsList =
                addDrawableChild(
                        new SettingsList(paneX, paneWidth, contentTop, Math.max(18, frameBottom - contentTop - 48)));

        int footerY = frameBottom - 26;
        int resetWidth = Math.min(122, toolbarWidth / 2);
        undoButton =
                menuButton(
                        label("undo"),
                        left,
                        footerY,
                        Math.min(82, toolbarWidth - resetWidth - 4),
                        () -> {
                            draft.undo();
                            refreshRows();
                        },
                        false);
        resetButton =
                menuButton(
                        label("reset_section"),
                        sideLeft,
                        frameTop + 36,
                        sideButtonWidth,
                        () -> {
                            draft.reset(
                                    section().keys().stream()
                                            .filter(TerrainDiffusionSettingsScreen::visibleSetting)
                                            .toList());
                            refreshRows();
                        },
                        false);
        errorWidget = addDrawableChild(new TextWidget(left, frameBottom - 41, toolbarWidth, 10, message, textRenderer));
        refreshRows();
    }

    @Override
    public void onDisplayed() {
        openingStartedAt = -1;
        lastFrameAt = 0;
    }

    static float transitionAmount(long elapsedMs, int durationMs) {
        float remaining = 1f - Math.clamp(elapsedMs / (float) durationMs, 0f, 1f);
        return remaining * remaining * remaining;
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        long now = Util.getMeasuringTimeMs();
        if (openingStartedAt == -1) openingStartedAt = now;
        float opening = transitionAmount(now - openingStartedAt, 260);
        sectionTransition = transitionAmount(now - sectionChangedAt, 220);
        hoverStep = (float) (1 - Math.exp(-Math.min(100, now - lastFrameAt) * 0.018));
        lastFrameAt = now;

        int backgroundWidth = Math.max(width, (int) Math.ceil(height * (1672.0 / 941.0)));
        int backgroundHeight = Math.max(height, (int) Math.ceil(width * (941.0 / 1672.0)));
        context.drawTexture(
                RenderPipelines.GUI_TEXTURED,
                BACKGROUND,
                (width - backgroundWidth) / 2,
                (height - backgroundHeight) / 2,
                0f,
                0f,
                backgroundWidth,
                backgroundHeight,
                1672,
                941,
                1672,
                941);
        context.fillGradient(
                0,
                0,
                width,
                height,
                ColorHelper.lerp(opening, 0x48101820, 0xB0101820),
                ColorHelper.lerp(opening, 0xC0101820, 0xE8101820));
        panel(
                context,
                frameLeft - 1,
                frameTop - 1,
                frameRight - frameLeft + 2,
                frameBottom - frameTop + 2,
                0x70101820,
                0x344D5865);
        panel(context, frameLeft, frameTop + 29, sidebarWidth, frameBottom - frameTop - 29, SIDEBAR, LINE);
        context.drawText(
                textRenderer,
                Text.literal("European Wilderness").formatted(Formatting.BOLD),
                frameLeft + 8,
                frameTop + 6,
                LIGHT,
                false);
        context.drawText(textRenderer, label("subtitle"), frameLeft + 8, frameTop + 19, MUTED, false);
        context.fillGradient(paneX, frameTop + 28, frameRight, frameBottom, 0x18101820, 0x70101820);
        context.fill(paneX + 8, frameBottom - 48, frameRight - 8, frameBottom - 47, LINE);
        Text heading =
                switch (view) {
                    case CHANGED -> changedButton.getMessage();
                    case FAVORITES -> label("favorites");
                    case RECENT -> label("recent");
                    case SECTION -> label(query.isBlank() ? "page." + section().name() : "search");
                };
        drawIcon(context, section().name(), paneX + 10, frameTop + 34, AMBER);
        int headingColor = ColorHelper.lerp(sectionTransition, LIGHT, AMBER);
        context.drawText(
                textRenderer,
                heading.copy().formatted(Formatting.BOLD),
                paneX + 34,
                frameTop + 34,
                headingColor,
                false);
        context.drawText(
                textRenderer,
                textRenderer.trimToWidth(label("description." + section().name()).getString(), frameRight - paneX - 44),
                paneX + 34,
                frameTop + 49,
                MUTED,
                false);
    }

    private MenuButton menuButton(Text title, int x, int y, int width, Runnable action, boolean accent) {
        var button = new MenuButton(title, x, y, width, action);
        button.accent = accent;
        return addDrawableChild(button);
    }

    private void addSearch(int x, int y, int width) {
        var search = new TextFieldWidget(textRenderer, x, y, width, 20, label("search"));
        search.setPlaceholder(label("search"));
        search.setText(query);
        search.setChangedListener(value -> {
            query = value;
            settingsList.setScrollY(0);
            refreshRows();
        });
        addDrawableChild(search);
    }

    private void addFilters(int x, int y, int size) {
        changedButton = menuButton(label("changed", 0), x, y, size, () -> toggle(View.CHANGED), false);
        menuButton(label("favorites"), x + size + 3, y, size, () -> toggle(View.FAVORITES), view == View.FAVORITES)
                        .active =
                preferences != null;
        menuButton(label("recent"), x + (size + 3) * 2, y, size, () -> toggle(View.RECENT), view == View.RECENT)
                        .active =
                draft != null;
    }

    private TerrainSettingsCatalog.Page section() {
        return TerrainSettingsCatalog.PAGES.get(page);
    }

    private void selectPage(int page) {
        if (this.page != page || view != View.SECTION || !query.isBlank()) sectionChangedAt = Util.getMeasuringTimeMs();
        this.page = page;
        this.view = View.SECTION;
        this.query = "";
        clearAndInit();
    }

    private void toggle(View filter) {
        view = view == filter ? View.SECTION : filter;
        sectionChangedAt = Util.getMeasuringTimeMs();
        clearAndInit();
    }

    private void openProfiles() {
        client.setScreen(
                new SettingsProfileScreen(
                        this,
                        preferences,
                        draft::values,
                        (name, values) -> {
                            draft.apply(values);
                            message = label("profile_selected", name);
                        }));
    }

    private void setMessage(Text text) {
        message = text;
        if (errorWidget != null) {
            errorWidget.setMessage(
                    Text.literal(textRenderer.trimToWidth(text.getString(), frameRight - paneX - 16))
                            .setStyle(text.getStyle()));
            errorWidget.setTooltip(text.getString().isEmpty() ? null : Tooltip.of(text));
        }
    }

    @Override
    public void tick() {
        if (!refreshPending || client.mouse.wasLeftButtonClicked()) return;
        var row = settingsList.getFocused();
        if (row == null || !(row.getFocused() instanceof TextFieldWidget field) || !field.isFocused()) refreshRows();
    }

    private void refreshRows() {
        refreshPending = false;
        var rows = new ArrayList<SettingRow>();
        if (draft != null) {
            List<String> keys =
                    switch (view) {
                        case SECTION -> query.isBlank() ? section().keys() : TerrainSettingsCatalog.KEYS;
                        case CHANGED -> draft.changedKeys();
                        case FAVORITES -> TerrainSettingsCatalog.KEYS.stream().filter(preferences::favorite).toList();
                        case RECENT -> recentKeys();
                    };
            for (String key : keys) {
                if (visibleSetting(key) && matches(key)) rows.add(setting(key));
            }
        }
        if (view == View.SECTION && query.isBlank()) addSectionInfo(rows);
        if (rows.isEmpty() && draft != null) rows.add(info(label("no_results")));
        settingsList.update(rows);
        updateButtons();
        setMessage(message);
    }

    private List<String> recentKeys() {
        var keys = new LinkedHashSet<>(draft.changedKeys());
        if (preferences != null) keys.addAll(preferences.recent());
        return List.copyOf(keys);
    }

    private static boolean visibleSetting(String key) {
        return !key.equals("compat.voxy.coarse_experimental") && !key.equals("performance.water_write_behind");
    }

    private boolean matches(String key) {
        if (query.isBlank()) return true;
        String text =
                (key + " " + label("field." + key).getString() + " " + label("tooltip." + key).getString())
                        .toLowerCase(Locale.ROOT);
        for (String term : query.strip().toLowerCase(Locale.ROOT).split("\\s+")) if (!text.contains(term)) return false;
        return true;
    }

    private void updateButtons() {
        undoButton.active = draft != null && draft.canUndo();
        resetButton.active =
                draft != null
                        && view == View.SECTION
                        && section().keys().stream().anyMatch(TerrainDiffusionSettingsScreen::visibleSetting);
        changedButton.active = draft != null;
        long changedCount =
                draft == null
                        ? 0
                        : draft.changedKeys().stream().filter(TerrainDiffusionSettingsScreen::visibleSetting).count();
        Text changed = label("changed", changedCount);
        changedButton.setMessage(changed);
        changedButton.accent = view == View.CHANGED;
        changedButton.setTooltip(Tooltip.of(changed));
    }

    private void edit(String key, String value, boolean continuous) {
        draft.edit(key, value, continuous);
        if (view == View.CHANGED && !draft.changed(key)) refreshPending = true;
        updateButtons();
        setMessage(Text.empty());
    }

    private void favorite(String key, ClickableWidget pressed) {
        try {
            preferences.toggleFavorite(key);
            pressed.setMessage(star(key));
            if (view == View.FAVORITES) refreshPending = true;
        } catch (IOException failure) {
            setMessage(label("preferences_error").formatted(Formatting.RED));
        }
    }

    private Text star(String key) {
        return preferences != null && preferences.favorite(key)
                ? Text.literal("★").formatted(Formatting.GOLD)
                : Text.literal("☆");
    }

    private SettingRow setting(String key) {
        Text label = label("field." + key);
        Text description = label("tooltip." + key);
        String application = TerrainSettingsCatalog.application(key);
        Text badge = label("apply." + application).formatted(Formatting.GOLD);
        var help = Tooltip.of(description.copy().append("\n").append(label("apply." + application + "_tip")));
        var caption = new TextWidget(0, 0, 145, 9, label.copy().formatted(Formatting.BOLD), textRenderer);
        caption.setTooltip(help);
        var detail = new TextWidget(0, 0, 145, 9, description.copy().formatted(Formatting.GRAY), textRenderer);
        detail.setTooltip(help);
        var note = new TextWidget(0, 0, 145, 9, badge, textRenderer);
        note.setTooltip(help);
        String[] choices = TerrainSettingsCatalog.choices(key);
        var percent = TerrainSettingsCatalog.PERCENTAGES.get(key);
        ClickableWidget control;
        if (choices != null) {
            control =
                    ButtonWidget.builder(
                                    valueLabel(this.draft.get(key)),
                                    pressed -> {
                                        String current = this.draft.get(key);
                                        int index = Arrays.asList(choices).indexOf(current);
                                        String next = choices[(index + 1) % choices.length];
                                        edit(key, next, false);
                                        pressed.setMessage(valueLabel(next));
                                    })
                            .dimensions(0, 0, 145, 20)
                            .tooltip(help)
                            .build();
        } else if (percent != null) {
            int initial;
            try {
                initial = Integer.parseInt(this.draft.get(key));
            } catch (NumberFormatException invalid) {
                initial = percent.defaultValue();
            }
            initial = Math.clamp(initial, percent.minimum(), percent.maximum());
            int range = percent.maximum() - percent.minimum();
            control =
                    new SliderWidget(
                            0,
                            0,
                            145,
                            20,
                            Text.literal(initial + "%"),
                            (initial - percent.minimum()) / (double) range) {
                        private double displayedValue = value;
                        private long lastFrame = System.nanoTime();

                        private int percentage() {
                            return percent.minimum() + (int) Math.round(this.value * range);
                        }

                        @Override
                        public void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
                            long now = System.nanoTime();
                            displayedValue +=
                                    (value - displayedValue)
                                            * (1 - Math.exp(-Math.min(0.1, (now - lastFrame) / 1.0e9) * 22));
                            lastFrame = now;
                            int x = getX(), y = getY();
                            int right = getRight() - 5;

                            panel(context, x + 5, y + 14, right - x - 5, 3, 0xFF414B55, 0xFF56616B);
                            int thumb = x + 5 + (int) Math.round((right - x - 5) * displayedValue);
                            context.fill(x + 5, y + 14, thumb, y + 16, AMBER);
                            panel(context, thumb - 3, y + 12, 7, 7, 0xFFFFD78B, AMBER);
                            context.drawText(
                                    textRenderer,
                                    getMessage(),
                                    getX() + (getWidth() - textRenderer.getWidth(getMessage())) / 2,
                                    y + 2,
                                    LIGHT,
                                    false);
                            if (isFocused()) context.drawStrokedRectangle(x, y, getWidth(), getHeight(), AMBER);
                        }

                        @Override
                        protected void updateMessage() {
                            this.setMessage(Text.literal(percentage() + "%"));
                        }

                        @Override
                        protected void applyValue() {
                            edit(key, Integer.toString(percentage()), true);
                        }
                    };
            control.setTooltip(help);
        } else {
            TextFieldWidget field = new TextFieldWidget(this.textRenderer, 0, 0, 145, 20, label);
            field.setMaxLength(11);
            field.setTooltip(help);
            field.setText(this.draft.get(key));
            field.setChangedListener(value -> edit(key, value, true));
            control = field;
        }
        var star = new MenuButton(star(key), 0, 0, 20, () -> {});
        star.action = () -> favorite(key, star);
        star.setTooltip(Tooltip.of(label("favorite_tip")));
        star.active = preferences != null;
        var row = new SettingRow(caption, detail, note, control, star);
        row.icon = TerrainSettingsCatalog.PAGES.get(TerrainSettingsCatalog.pageFor(key)).name();
        return row;
    }

    private final class SettingsList extends ElementListWidget<SettingRow> {
        SettingsList(int x, int width, int top, int height) {
            super(TerrainDiffusionSettingsScreen.this.client, width, height, top, 62);
            setX(x);
            centerListVertically = false;
        }

        @Override
        public int getRowWidth() {
            return getWidth() - 20;
        }

        @Override
        protected void drawMenuListBackground(DrawContext context) {}

        @Override
        protected void drawHeaderAndFooterSeparators(DrawContext context) {}

        void update(List<SettingRow> rows) {
            double scroll = getScrollY();
            replaceEntries(rows);
            setScrollY(scroll);
        }
    }

    private final class SectionsList extends ElementListWidget<SectionRow> {
        SectionsList(int height) {
            super(TerrainDiffusionSettingsScreen.this.client, sidebarWidth, height, frameTop + 66, 23);
            setX(frameLeft);
            centerListVertically = false;
            var pages = TerrainSettingsCatalog.PAGES;
            java.util.stream.IntStream.range(0, pages.size())
                    .boxed()
                    .sorted(
                            java.util.Comparator.comparingInt(index -> {
                                int order = WORLD_SECTIONS.indexOf(pages.get(index).name());
                                return order < 0 ? WORLD_SECTIONS.size() : order;
                            }))
                    .forEach(index -> addEntry(new SectionRow(index)));
        }

        @Override
        public int getRowWidth() {
            return getWidth() - 14;
        }

        @Override
        protected void drawMenuListBackground(DrawContext context) {}

        @Override
        protected void drawHeaderAndFooterSeparators(DrawContext context) {}
    }

    private final class SectionRow extends ElementListWidget.Entry<SectionRow> {
        private final MenuButton button;

        SectionRow(int index) {
            var section = TerrainSettingsCatalog.PAGES.get(index);
            button = new MenuButton(label("page." + section.name()), 0, 0, 80, () -> selectPage(index));
            button.sidebar = true;
            button.icon = section.name();
            button.accent = view == View.SECTION && query.isBlank() && page == index;
        }

        @Override
        public List<? extends Element> children() {
            return List.of(button);
        }

        @Override
        public List<? extends Selectable> selectableChildren() {
            return List.of(button);
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, boolean hovered, float delta) {
            button.setDimensionsAndPosition(getContentWidth(), 21, getContentX(), getContentY());
            button.render(context, mouseX, mouseY, delta);
        }
    }

    private final class MenuButton extends ClickableWidget {
        private Runnable action;
        private boolean accent;
        private boolean sidebar;
        private String icon;
        private float hover;

        MenuButton(Text title, int x, int y, int width, Runnable action) {
            super(x, y, width, 20, title);
            this.action = action;
            setTooltip(Tooltip.of(title));
        }

        @Override
        protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
            hover += ((isSelected() && active ? 1 : 0) - hover) * hoverStep;
            int fill =
                    !active
                            ? 0x58222B33
                            : accent ? (sidebar ? 0x9A68502C : 0xD09D742F) : sidebar ? 0x08141C24 : 0xA02A333D;
            int color = !active ? 0xFF7F8993 : LIGHT;
            int x = getX(), y = getY();
            if (accent) panel(context, x - 1, y - 1, getWidth() + 2, getHeight() + 2, 0x186D5128, 0x407D6238);
            panel(
                    context,
                    x,
                    y,
                    getWidth(),
                    getHeight(),
                    fill,
                    accent || isFocused() ? 0xDDE9B65C : sidebar ? 0x003D4854 : LINE);
            panel(context, x, y, getWidth(), getHeight(), ((int) (hover * 24) << 24) | 0xFFE5B0, 0x00000000);
            if (icon != null) drawIcon(context, icon, x + 7, y + 4, accent ? AMBER : MUTED);
            String text = textRenderer.trimToWidth(getMessage().getString(), getWidth() - (icon == null ? 10 : 32));
            int textX = sidebar ? x + (icon == null ? 7 : 29) : x + (getWidth() - textRenderer.getWidth(text)) / 2;
            context.drawText(textRenderer, text, textX, y + (getHeight() - 8) / 2, color, false);
            if (isHovered() && active) context.setCursor(StandardCursors.POINTING_HAND);
        }

        @Override
        public void onClick(Click click, boolean doubled) {
            action.run();
        }

        @Override
        public boolean keyPressed(KeyInput input) {
            if (!active || !visible || !input.isEnterOrSpace()) return false;
            playDownSound(client.getSoundManager());
            action.run();
            return true;
        }

        @Override
        protected void appendClickableNarrations(NarrationMessageBuilder builder) {
            builder.put(NarrationPart.TITLE, getNarrationMessage(getMessage()));
            if (active)
                builder.put(
                        NarrationPart.USAGE,
                        Text.translatable(
                                isFocused() ? "narration.button.usage.focused" : "narration.button.usage.hovered"));
        }
    }

    private final class SettingRow extends ElementListWidget.Entry<SettingRow> {
        private String icon;
        private final TextWidget caption;
        private final TextWidget detail;
        private final TextWidget note;
        private final ClickableWidget control;
        private final ClickableWidget star;
        private final List<ClickableWidget> widgets;
        private float hover;

        SettingRow(
                TextWidget caption, TextWidget detail, TextWidget note, ClickableWidget control, ClickableWidget star) {
            this.caption = caption;
            this.detail = detail;
            this.note = note;
            this.control = control;
            this.star = star;
            var entries = new ArrayList<ClickableWidget>();
            if (caption != null) entries.add(caption);
            if (detail != null) entries.add(detail);
            if (note != null) entries.add(note);
            if (control != null) entries.add(control);
            if (star != null) entries.add(star);
            widgets = List.copyOf(entries);
        }

        @Override
        public List<? extends Element> children() {
            return widgets;
        }

        @Override
        public List<? extends Selectable> selectableChildren() {
            return widgets;
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, boolean hovered, float delta) {
            int left = getContentX();
            int y = getContentY();
            int width = getContentWidth();
            hover += ((hovered && control != null ? 1 : 0) - hover) * hoverStep;
            int restingFill = ColorHelper.lerp(sectionTransition, 0xBA1E272F, 0xDA45382B);
            int fill = ColorHelper.lerp(hover, restingFill, 0xD12D3741);
            panel(context, left - 2, y, width + 4, 54, fill, LINE);
            left += 9;
            width -= 18;
            if (icon != null && width >= 350) {
                drawIcon(context, icon, left + 1, y + 20, AMBER);
                left += 26;
                width -= 26;
            }
            int controlWidth = Math.min(138, Math.max(44, width / 3));
            int captionWidth = control == null ? width : Math.max(12, width - controlWidth - 32);
            if (caption != null) {
                caption.setMaxWidth(captionWidth);
                caption.setPosition(left, y + (detail == null ? 20 : 9));
            }
            if (detail != null) {
                detail.setMaxWidth(captionWidth);
                detail.setPosition(left, y + 23);
                note.setMaxWidth(captionWidth - 8);
                note.setPosition(left + 4, y + 38);
                int badgeWidth =
                        Math.min(
                                captionWidth,
                                net.minecraft.client.MinecraftClient.getInstance()
                                                .textRenderer
                                                .getWidth(note.getMessage())
                                        + 8);
                panel(context, left, y + 35, badgeWidth, 15, 0x80574326, 0x886F562C);
            }
            if (control != null) {
                control.setWidth(caption == null ? width : controlWidth);
                control.setPosition(caption == null ? left : left + width - controlWidth - 24, y + 17);
            }
            if (star != null) star.setPosition(left + width - 20, y + 17);
            for (var widget : widgets) widget.render(context, mouseX, mouseY, delta);
        }
    }

    private SettingRow info(Text text) {
        var caption = new TextWidget(0, 0, 250, 9, text.copy().formatted(Formatting.GRAY), textRenderer);
        caption.setTooltip(Tooltip.of(text));
        return new SettingRow(caption, null, null, null, null);
    }

    @FunctionalInterface
    private interface VisualAction {
        void run() throws Exception;
    }

    private SettingRow visualRow(String name, boolean enabled, VisualAction action) {
        var button =
                ButtonWidget.builder(
                                label(name),
                                pressed -> {
                                    try {
                                        action.run();
                                        setMessage(label("visual_applied").formatted(Formatting.GREEN));
                                    } catch (Exception | LinkageError failure) {
                                        org.slf4j.LoggerFactory.getLogger(getClass())
                                                .warn("Visual settings action failed", failure);
                                        setMessage(label("visual_error").formatted(Formatting.RED));
                                    }
                                })
                        .dimensions(0, 0, 150, 20)
                        .tooltip(Tooltip.of(label(name + ".tip").append("\n").append(label("apply.now"))))
                        .build();
        button.active = enabled;
        return new SettingRow(null, null, null, button, null);
    }

    private void addSectionInfo(List<SettingRow> rows) {
        switch (section().name()) {
            case "visibility" -> {
                int viewDistance = client.options.getViewDistance().getValue();
                var voxy = ExternalVisualSettings.voxyState();
                rows.add(info(label("view.minecraft", viewDistance, viewDistance * 16)));
                rows.add(
                        info(
                                voxy.present()
                                        ? label(
                                                voxy.enabled() && voxy.rendering() ? "view.voxy_on" : "view.voxy_off",
                                                voxy.chunks())
                                        : label("view.no_voxy")));
                String shader = ExternalVisualSettings.shaderName();
                rows.add(info(label("view.shader", shader.isBlank() ? "—" : shader)));
                rows.add(
                        visualRow(
                                "open_voxy",
                                voxy.present(),
                                () -> client.setScreen(ExternalVisualSettings.voxy(this))));
            }
            case "compatibility" -> {
                String version = RoadWeaverTerrain.installedVersion();
                rows.add(
                        info(
                                version == null
                                        ? label("roadweaver.missing")
                                        : RoadWeaverTerrain.supports(version)
                                                ? label("roadweaver.supported", version)
                                                : label(
                                                        "roadweaver.unsupported",
                                                        version,
                                                        RoadWeaverTerrain.SUPPORTED_VERSION)));
                rows.add(info(installedVersion("Chunky", "chunky")));
                rows.add(info(installedVersion("C2ME", "c2me")));
                rows.add(info(installedVersion("Alex's Mobs", "alexsmobs")));
            }
            case "animals" -> rows.add(info(installedVersion("Alex's Mobs", "alexsmobs")));
            case "generation" -> rows.add(info(waterMode()));
            default -> {}
        }
        rows.add(info(label("bounds." + section().name())));
    }

    private static Text installedVersion(String name, String id) {
        var version =
                FabricLoader.getInstance()
                        .getModContainer(id)
                        .map(mod -> mod.getMetadata().getVersion().getFriendlyString());
        return label("mod_version", name, version.<Text>map(Text::literal).orElseGet(() -> label("mod_missing")));
    }

    private Text waterMode() {
        var session = WorldSession.current();
        var server = client.getServer();
        var overworld = server == null ? null : server.getOverworld();
        if (overworld != null
                && overworld.getChunkManager().getChunkGenerator().getBiomeSource()
                        instanceof TerrainDiffusionBiomeSource
                && session != null
                && session.isActive()) {
            if (session.automaticWater() != null) return label("water.automatic");
            if (session.preparedWater() != null) return label("water.prepared");
        }
        return label("water.new_world");
    }

    private static void panel(DrawContext context, int x, int y, int width, int height, int fill, int border) {
        if (width <= 0 || height <= 0) return;
        // Two-pixel corners remain crisp at every Minecraft GUI scale.
        context.fill(x + 2, y, x + width - 2, y + 1, border);
        context.fill(x + 2, y + height - 1, x + width - 2, y + height, border);
        context.fill(x, y + 2, x + 1, y + height - 2, border);
        context.fill(x + width - 1, y + 2, x + width, y + height - 2, border);
        context.fill(x + 1, y + 1, x + width - 1, y + height - 1, fill);
    }

    private static void drawIcon(DrawContext context, String icon, int x, int y, int color) {
        String shape =
                switch (icon) {
                    case "terrain" -> "000,060,090,108,114,222,441,801,fff";
                    case "rivers" -> "000,318,4a4,843,000,318,4a4,843,000,318,4a4,843";
                    case "nature" -> "03e,0c2,102,204,248,290,320,440,400,800";
                    case "animals" -> "000,198,198,666,666,000,0f0,1f8,3fc,3fc,198";
                    case "models", "voxy" -> "060,198,606,999,861,861,861,861,492,264,198,060";
                    case "explorer", "visibility" -> "000,0f0,30c,402,861,891,891,861,402,30c,0f0";
                    case "generation", "lod" -> "060,198,606,801,606,198,861,606,198,861,606,198";
                    case "performance" -> "0f0,30c,402,801,821,841,881,881,801,801,402";
                    default -> "000,444,444,7fc,444,444,444,7fc,444,444";
                };
        String[] rows = shape.split(",");
        for (int row = 0; row < rows.length; row++) {
            int bits = Integer.parseInt(rows[row], 16);
            for (int column = 0; column < 12; column++) {
                if ((bits & (1 << (11 - column))) != 0)
                    context.fill(x + column, y + row, x + column + 1, y + row + 1, color);
            }
        }
    }

    private static Text valueLabel(String value) {
        return label("value." + value);
    }

    private void save() {
        String invalid = TerrainDiffusionConfigEditor.validate(draft.values());
        if (invalid != null) {
            boolean incompatible =
                    "performance.prefetch".equals(invalid)
                            && "adaptive".equals(draft.get(invalid))
                            && "prepared_only".equals(draft.get("preparation.mode"));
            message =
                    (incompatible ? label("adaptive_requires_on_demand") : label("invalid", label("field." + invalid)))
                            .formatted(Formatting.RED);
            selectPage(TerrainSettingsCatalog.pageFor(invalid));
            return;
        }
        try {
            TerrainDiffusionConfigEditor.save(configPath, draft.values());
        } catch (IOException failure) {
            setMessage(label("save_error", failure.getMessage()).formatted(Formatting.RED));
            return;
        }
        if (preferences != null)
            try {
                preferences.recordRecent(draft.changedKeys());
            } catch (IOException failure) {
                org.slf4j.LoggerFactory.getLogger(getClass())
                        .warn("Settings saved, but recent changes could not be recorded", failure);
            }
        close();
    }

    @Override
    public void close() {
        client.setScreen(parent);
    }
}
