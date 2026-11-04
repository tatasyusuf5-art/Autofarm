package com.yusuf.autofarm;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * Mod 4: sadece baktığın bloğa çalışır. Yürümez, bakış çevirmez.
 * Her tick: olgunsa kır -> boşsa ek -> kemik tozu bas (birkaç kez).
 * Hotbarda: çapa, kemik tozu ve ekin (havuç vs.) olmalı.
 */
public final class LookFarm {
    /** Tick başına kemik tozu denemesi. Fazlası sunucuda tüketilmez (olgun ekine tozlanmaz). */
    private static final int BONE_PER_TICK = 3;

    private LookFarm() {
    }

    /** @return ekranda gösterilecek uyarı, sorun yoksa null */
    public static String tick(MinecraftClient mc, AutoFarmConfig cfg) {
        ClientPlayerEntity p = mc.player;
        ClientWorld w = mc.world;
        ClientPlayerInteractionManager im = mc.interactionManager;
        if (p == null || w == null || im == null) return null;

        if (!(mc.crosshairTarget instanceof BlockHitResult look) || look.getType() != HitResult.Type.BLOCK) {
            return null;
        }

        // Baktığın blok ekin mi, çiftlik toprağı mı?
        BlockPos hp = look.getBlockPos();
        BlockState hs = w.getBlockState(hp);
        BlockPos farm;
        BlockPos crop;
        if (hs.getBlock() instanceof CropBlock) {
            crop = hp;
            farm = hp.down();
        } else if (hs.isOf(Blocks.FARMLAND)) {
            farm = hp;
            crop = hp.up();
        } else {
            return null;
        }

        if (!w.getBlockState(farm).isOf(Blocks.FARMLAND)) return null;

        BlockState cs = w.getBlockState(crop);
        boolean hasCrop = cs.getBlock() instanceof CropBlock;
        if (hasCrop && !cfg.isSelected(cs.getBlock())) return null;
        if (!hasCrop && !cs.isAir()) return null;

        // Hotbar taraması: çapa (en yüksek Servet), kemik tozu
        PlayerInventory inv = p.getInventory();
        int hoe = -1;
        int bone = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.getStack(i);
            if (bone < 0 && s.isOf(Items.BONE_MEAL)) bone = i;
            if (s.isIn(ItemTags.HOES) && (hoe < 0 || fortune(s) > fortune(inv.getStack(hoe)))) hoe = i;
        }

        Block brokenBlock = null;

        // 1) Olgunsa kır
        if (hasCrop && ((CropBlock) cs.getBlock()).isMature(cs)) {
            if (cfg.requireHoe && hoe < 0) return "Hotbara çapa koy! (Çapa zorunluluğu açık)";
            if (cfg.requireFortune && (hoe < 0 || fortune(inv.getStack(hoe)) < 3)) {
                return "Hotbarda Servet III alet olmalı! (Servet III zorunluluğu açık)";
            }
            if (hoe >= 0) inv.selectedSlot = hoe;
            im.attackBlock(crop, Direction.UP);
            p.swingHand(Hand.MAIN_HAND);
            brokenBlock = cs.getBlock();
            hasCrop = false;
        }

        // 2) Boşsa ek (aynı tick'te, kırdıktan hemen sonra)
        if (!hasCrop) {
            int seed = findSeed(inv, cfg, brokenBlock);
            if (seed < 0) return "Hotbara ekin koy! (havuç, patates vs.)";
            inv.selectedSlot = seed;
            BlockHitResult place = new BlockHitResult(
                    Vec3d.ofCenter(farm).add(0.0, 0.4375, 0.0), Direction.UP, farm, false);
            ActionResult r = im.interactBlock(p, Hand.MAIN_HAND, place);
            if (!r.isAccepted()) return null;
            p.swingHand(Hand.MAIN_HAND);
        }

        // 3) Kemik tozu
        if (bone < 0) return "Hotbarda kemik tozu yok!";
        inv.selectedSlot = bone;
        BlockHitResult bh = new BlockHitResult(Vec3d.ofCenter(crop), Direction.UP, crop, false);
        for (int i = 0; i < BONE_PER_TICK; i++) {
            im.interactBlock(p, Hand.MAIN_HAND, bh);
        }
        p.swingHand(Hand.MAIN_HAND);
        return null;
    }

    /** Hotbarda seçili ekinlerden birini bul; kırdığımız ekinle aynı olanı tercih et. */
    private static int findSeed(PlayerInventory inv, AutoFarmConfig cfg, Block preferred) {
        int any = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty() || !(s.getItem() instanceof BlockItem bi)) continue;
            Block b = bi.getBlock();
            if (!(b instanceof CropBlock) || !cfg.isSelected(b)) continue;
            if (b == preferred) return i;
            if (any < 0) any = i;
        }
        return any;
    }

    private static int fortune(ItemStack stack) {
        ItemEnchantmentsComponent ench = stack.getEnchantments();
        for (RegistryEntry<Enchantment> e : ench.getEnchantments()) {
            if (e.matchesKey(Enchantments.FORTUNE)) return ench.getLevel(e);
        }
        return 0;
    }
}
