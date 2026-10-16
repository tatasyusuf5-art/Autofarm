package com.yusuf.autofarm;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.CropBlock;
import net.minecraft.block.NetherWartBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class AutoFarmClient implements ClientModInitializer {
    private static final int RADIUS = 4;
    private static final double REACH_SQ = 4.5 * 4.5;
    private static final int DELAY_TICKS = 2;     // kaç tickte bir çalışsın
    private static final int MAX_PER_TICK = 3;    // her çalışmada en fazla kaç blok

    private static final String[] MODE_NAMES = {
            "1: Kır (olgun ekinleri kırar)",
            "2: Ek (boş tarlaları eker)",
            "3: Kır + Ek (kırar ve anında geri eker)"
    };

    private boolean enabled = false;
    private int mode = 0;
    private int cooldown = 0;
    private int warnCooldown = 0;

    private KeyBinding toggleKey;
    private KeyBinding modeKey;

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.autofarm.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_R, "category.autofarm"));
        modeKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.autofarm.mode", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G, "category.autofarm"));

        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);

        // Komutlar: /autofarm  ve  /autofarm mode <1-3>
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("autofarm")
                        .executes(ctx -> {
                            toggle(MinecraftClient.getInstance());
                            return 1;
                        })
                        .then(ClientCommandManager.literal("mode")
                                .then(ClientCommandManager.argument("n", IntegerArgumentType.integer(1, 3))
                                        .executes(ctx -> {
                                            mode = IntegerArgumentType.getInteger(ctx, "n") - 1;
                                            say(MinecraftClient.getInstance(), "Mod -> " + MODE_NAMES[mode]);
                                            return 1;
                                        })))));
    }

    private void toggle(MinecraftClient mc) {
        enabled = !enabled;
        cooldown = 0;
        say(mc, "AutoFarm " + (enabled ? "AÇIK" : "KAPALI") + " | Mod " + MODE_NAMES[mode]);
    }

    private void cycleMode(MinecraftClient mc) {
        mode = (mode + 1) % MODE_NAMES.length;
        say(mc, "Mod -> " + MODE_NAMES[mode]);
    }

    private void say(MinecraftClient mc, String msg) {
        if (mc.player != null) {
            mc.player.sendMessage(Text.literal(msg), true);
        }
    }

    private void onTick(MinecraftClient mc) {
        while (toggleKey.wasPressed()) toggle(mc);
        while (modeKey.wasPressed()) cycleMode(mc);

        if (warnCooldown > 0) warnCooldown--;

        if (!enabled || mc.player == null || mc.world == null || mc.interactionManager == null
                || mc.currentScreen != null || mc.player.isSpectator()) {
            return;
        }
        if (++cooldown < DELAY_TICKS) return;
        cooldown = 0;

        // Ekim gerektiren modlarda eldeki ekini kontrol et
        Hand plantHand = null;
        if (mode == 2) {
            // Mod 3: ekin 2. elde (offhand) olmak zorunda
            if (isSeed(mc.player.getStackInHand(Hand.OFF_HAND))) {
                plantHand = Hand.OFF_HAND;
            } else {
                warn(mc, "Mod 3 için 2. ele ekin (tohum/havuç/patates...) al!");
                return;
            }
        } else if (mode == 1) {
            if (isSeed(mc.player.getStackInHand(Hand.MAIN_HAND))) {
                plantHand = Hand.MAIN_HAND;
            } else if (isSeed(mc.player.getStackInHand(Hand.OFF_HAND))) {
                plantHand = Hand.OFF_HAND;
            } else {
                warn(mc, "Ekmek için elinde ekin olmalı!");
                return;
            }
        }

        Vec3d eye = mc.player.getEyePos();
        BlockPos origin = mc.player.getBlockPos();
        List<BlockPos> targets = new ArrayList<>();
        for (BlockPos p : BlockPos.iterate(origin.add(-RADIUS, -1, -RADIUS), origin.add(RADIUS, 1, RADIUS))) {
            if (eye.squaredDistanceTo(Vec3d.ofCenter(p)) <= REACH_SQ) {
                targets.add(p.toImmutable());
            }
        }
        targets.sort(Comparator.comparingDouble(p -> eye.squaredDistanceTo(Vec3d.ofCenter(p))));

        int done = 0;
        for (BlockPos pos : targets) {
            if (done >= MAX_PER_TICK) break;
            BlockState state = mc.world.getBlockState(pos);

            if (mode == 0 || mode == 2) {
                if (isMature(state)) {
                    mc.interactionManager.attackBlock(pos, Direction.UP);
                    mc.player.swingHand(Hand.MAIN_HAND);
                    if (mode == 2) {
                        // kırdığın anda geri ek
                        plant(mc, pos, plantHand);
                    }
                    done++;
                }
            } else if (mode == 1) {
                if (state.isAir() && plant(mc, pos, plantHand)) {
                    done++;
                }
            }
        }
    }

    private void warn(MinecraftClient mc, String msg) {
        if (warnCooldown == 0) {
            say(mc, msg);
            warnCooldown = 40;
        }
    }

    private static boolean isMature(BlockState state) {
        Block b = state.getBlock();
        if (b instanceof CropBlock crop) {
            return crop.isMature(state);
        }
        if (b instanceof NetherWartBlock) {
            return state.get(NetherWartBlock.AGE) >= 3;
        }
        return false;
    }

    private static boolean isSeed(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem bi)) return false;
        Block b = bi.getBlock();
        return b instanceof CropBlock || b instanceof NetherWartBlock;
    }

    private boolean plant(MinecraftClient mc, BlockPos pos, Hand hand) {
        if (hand == null) return false;
        ItemStack stack = mc.player.getStackInHand(hand);
        if (!isSeed(stack)) return false;
        Block crop = ((BlockItem) stack.getItem()).getBlock();

        if (!mc.world.getBlockState(pos).isAir()) return false;
        if (!crop.getDefaultState().canPlaceAt(mc.world, pos)) return false;

        BlockPos soil = pos.down();
        BlockHitResult hit = new BlockHitResult(
                Vec3d.ofCenter(soil).add(0, 0.5, 0), Direction.UP, soil, false);
        ActionResult result = mc.interactionManager.interactBlock(mc.player, hand, hit);
        if (result.isAccepted()) {
            mc.player.swingHand(hand);
            return true;
        }
        return false;
    }
}
