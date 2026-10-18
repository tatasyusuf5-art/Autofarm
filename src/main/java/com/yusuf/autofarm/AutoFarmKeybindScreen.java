package com.yusuf.autofarm;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/** G / R tuşlarını yeniden atamak için basit ekran: butona bas, sonra istediğin tuşa bas. */
public class AutoFarmKeybindScreen extends Screen {
    private final Screen parent;
    private final AutoFarmConfig cfg = AutoFarmConfig.get();

    private ButtonWidget toggleBtn;
    private ButtonWidget menuBtn;
    private boolean waitingToggle = false;
    private boolean waitingMenu = false;

    public AutoFarmKeybindScreen(Screen parent) {
        super(Text.literal("AutoFarm Tuş Atamaları"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int bw = 220;
        int left = this.width / 2 - bw / 2;
        int y = this.height / 2 - 40;

        toggleBtn = ButtonWidget.builder(toggleLabel(), b -> {
            waitingToggle = true;
            waitingMenu = false;
            b.setMessage(Text.literal("Bir tuşa bas...").formatted(Formatting.YELLOW));
        }).dimensions(left, y, bw, 20).build();
        addDrawableChild(toggleBtn);
        y += 26;

        menuBtn = ButtonWidget.builder(menuLabel(), b -> {
            waitingMenu = true;
            waitingToggle = false;
            b.setMessage(Text.literal("Bir tuşa bas...").formatted(Formatting.YELLOW));
        }).dimensions(left, y, bw, 20).build();
        addDrawableChild(menuBtn);
        y += 34;

        addDrawableChild(ButtonWidget.builder(Text.literal("Geri"), b -> this.close())
                .dimensions(left, y, bw, 20).build());
    }

    private Text toggleLabel() {
        return Text.literal("Aç/Kapat tuşu: " + keyName(cfg.keyToggle));
    }

    private Text menuLabel() {
        return Text.literal("Menü tuşu: " + keyName(cfg.keyMenu));
    }

    private static String keyName(int code) {
        return InputUtil.fromKeyCode(code, 0).getLocalizedText().getString();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (waitingToggle) {
            cfg.keyToggle = keyCode;
            waitingToggle = false;
            toggleBtn.setMessage(toggleLabel());
            cfg.save();
            return true;
        }
        if (waitingMenu) {
            cfg.keyMenu = keyCode;
            waitingMenu = false;
            menuBtn.setMessage(menuLabel());
            cfg.save();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2,
                this.height / 2 - 60, 0xFFFFFF);
    }

    @Override
    public void close() {
        if (this.client != null) this.client.setScreen(parent);
    }
}
