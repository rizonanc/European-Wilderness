package com.github.xandergos.terraindiffusionmc.client;

import com.github.xandergos.terraindiffusionmc.config.SettingsPreferences;
import com.github.xandergos.terraindiffusionmc.config.SettingsPresets;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.Selectable;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ElementListWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.widget.TextWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

final class SettingsProfileScreen extends Screen {
    private static final String PREFIX = "terrain-diffusion-mc.settings.";
    private final Screen parent;
    private final SettingsPreferences preferences;
    private final Supplier<Map<String, String>> draft;
    private final BiConsumer<Text, Map<String, String>> apply;
    private String name = "";
    private Text message = Text.empty();
    private TextWidget feedback;

    SettingsProfileScreen(
            Screen parent,
            SettingsPreferences preferences,
            Supplier<Map<String, String>> draft,
            BiConsumer<Text, Map<String, String>> apply) {
        super(Text.translatable(PREFIX + "profiles"));
        this.parent = parent;
        this.preferences = preferences;
        this.draft = draft;
        this.apply = apply;
    }

    @Override
    protected void init() {
        int center = width / 2;
        addDrawableChild(new TextWidget(0, 10, width, 12, title, textRenderer));
        var rows = new ArrayList<ProfileRow>();
        if (draft == null) {
            Text label = Text.translatable(PREFIX + "profile_current");
            rows.add(new ProfileRow(label, () -> select(label, Map.of()), null));
        }
        for (var preset : SettingsPresets.ALL) {
            if (draft == null && preset.id().equals("performance")) continue;
            Text label = Text.translatable(PREFIX + "preset." + preset.id());
            rows.add(new ProfileRow(label, () -> select(label, preset.values()), null));
        }
        if (preferences != null)
            for (var profile : preferences.profiles().entrySet()) {
                Text label = Text.literal(profile.getKey());
                rows.add(new ProfileRow(
                        label,
                        () -> select(label, profile.getValue()),
                        draft == null ? null : () -> confirmDelete(profile.getKey())));
            }
        boolean editable = draft != null && preferences != null;
        addDrawableChild(new ProfileList(Math.max(22, height - (editable ? 108 : 80)), rows));
        if (editable) {
            var field = new TextFieldWidget(
                    textRenderer, center - 145, height - 76, 178, 20, Text.translatable(PREFIX + "profile_name"));
            field.setMaxLength(32);
            field.setPlaceholder(Text.translatable(PREFIX + "profile_name"));
            field.setText(name);
            field.setChangedListener(value -> name = value);
            addDrawableChild(field);
            addDrawableChild(ButtonWidget.builder(Text.translatable(PREFIX + "profile_save"), button -> save())
                    .dimensions(center + 39, height - 76, 106, 20)
                    .build());
        }
        feedback = addDrawableChild(new TextWidget(0, height - 48, width, 10, message, textRenderer));
        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.back"), button -> close())
                .dimensions(center - 60, height - 28, 120, 20)
                .build());
    }

    private void select(Text label, Map<String, String> values) {
        apply.accept(label, values);
        close();
    }

    private void save() {
        try {
            preferences.saveProfile(name.trim(), draft.get());
            name = "";
            message = Text.translatable(PREFIX + "profile_saved").formatted(Formatting.GREEN);
            clearAndInit();
        } catch (IOException invalid) {
            message = Text.translatable(PREFIX + "profile_save_error").formatted(Formatting.RED);
            feedback.setMessage(message);
        }
    }

    private void confirmDelete(String profile) {
        client.setScreen(new ConfirmScreen(
                confirmed -> {
                    if (confirmed)
                        try {
                            preferences.deleteProfile(profile);
                            message = Text.empty();
                        } catch (IOException failure) {
                            message = Text.translatable(PREFIX + "preferences_error")
                                    .formatted(Formatting.RED);
                        }
                    client.setScreen(this);
                },
                Text.translatable(PREFIX + "profile_delete"),
                Text.literal(profile)));
    }

    @Override
    public void close() {
        client.setScreen(parent);
    }

    private final class ProfileList extends ElementListWidget<ProfileRow> {
        ProfileList(int height, List<ProfileRow> rows) {
            super(SettingsProfileScreen.this.client, SettingsProfileScreen.this.width, height, 29, 24);
            centerListVertically = false;
            replaceEntries(rows);
        }

        @Override
        public int getRowWidth() {
            return Math.min(294, SettingsProfileScreen.this.width - 24);
        }
    }

    private static final class ProfileRow extends ElementListWidget.Entry<ProfileRow> {
        private final ButtonWidget use;
        private final ButtonWidget delete;

        ProfileRow(Text label, Runnable select, Runnable remove) {
            use = ButtonWidget.builder(label, button -> select.run())
                    .dimensions(0, 0, 150, 20)
                    .tooltip(Tooltip.of(label))
                    .build();
            delete = remove == null
                    ? null
                    : ButtonWidget.builder(Text.translatable(PREFIX + "profile_delete"), button -> remove.run())
                            .dimensions(0, 0, 76, 20)
                            .build();
        }

        @Override
        public List<? extends Element> children() {
            return delete == null ? List.of(use) : List.of(use, delete);
        }

        @Override
        public List<? extends Selectable> selectableChildren() {
            return delete == null ? List.of(use) : List.of(use, delete);
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, boolean hovered, float delta) {
            use.setWidth(getContentWidth() - (delete == null ? 0 : 80));
            use.setPosition(getContentX(), getContentY());
            use.render(context, mouseX, mouseY, delta);
            if (delete != null) {
                delete.setPosition(getContentRightEnd() - 76, getContentY());
                delete.render(context, mouseX, mouseY, delta);
            }
        }
    }
}
