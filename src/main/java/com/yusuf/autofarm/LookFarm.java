package com.yusuf.autofarm;

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
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * Mod 4: baktığın ekin için oyuncunun elle yapacağı şeyi yapar. Hile yok:
 *  - Her tick'te EN FAZLA TEK eylem.
 *  - Eşya gerçekten ele alınır: hotbar slotu seçilir (1-9 tuşu gibi), sunucuyla senkron
 *    olması için bir sonraki tick beklenir, ondan sonra kullanılır.
 *  - Tıklama noktası gerçek bakış ışınından gelir (uydurma değil). Yürümez, bakış çevirmez.
 *
 * Akış (baktığın bloğa göre):
 *  olgun ekin  -> çapayı ele al, kır
 *  boş toprak  -> ekini kullan (2. elde olması önerilir; 2. el boşsa çantadan alınır)
 *  büyüyen ekin-> kemik tozunu ele al, kemik tozu bas
 *
 * Önerilen yerleşim: 2. elde ekin (havuç), hotbarda çapa + kemik tozu.
 * Hız ayarı eylemler arası bekleme süresini belirler (15 = her tick bir eylem).
 */
public final class LookFarm {
    private static int cooldown = 0;

    private LookFarm() {
    }

    private static int gapTicks(AutoFarmConfig cfg) {
        if (cfg.speed >= 13) return 1;
        if (cfg.speed >= 9) return 2;
        if (cfg.speed >= 5) return 3;
        return 4;
    }

    /** @return ekranda gösterilecek uyarı, sorun yoksa null */
    public static String tick(MinecraftClient mc, AutoFarmConfig cfg) {
        ClientPlayerEntity p = mc.player;
        ClientWorld w = mc.world;
        ClientPlayerInteractionManager im = mc.interactionManager;
        if (p == null || w == null || im == null) return null;

        if (cooldown > 0) {
            cooldown--;
            return null;
        }

        // Gerçek bakış ışını (oyuncunun şu an baktığı yer)
        HitResult hr = p.raycast(p.getBlockInteractionRange(), 1.0F, false);
        if (!(hr instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return null;

        BlockPos hp = hit.getBlockPos();
        BlockState hs = w.getBlockState(hp);
        int gap = gapTicks(cfg);

        if (hs.getBlock() instanceof CropBlock crop) {
            if (!cfg.isSelected(hs.getBlock())) return null;
            if (crop.isMature(hs)) {
                return harvest(p, im, cfg, hit, gap);
            }
            return boneMeal(p, im, hit, gap);
        }

        if (hs.isOf(Blocks.FARMLAND) && hit.getSide() == Direction.UP && w.getBlockState(hp.up()).isAir()) {
            return plant(p, im, cfg, hit, gap);
        }

        return null;
    }

    // ---------------------------------------------------------------- kır

    private static String harvest(ClientPlayerEntity p, ClientPlayerInteractionManager im,
                                  AutoFarmConfig cfg, BlockHitResult hit, int gap) {
        PlayerInventory inv = p.getInventory();
        int hoe = bestHoeSlot(inv);

        if (cfg.requireHoe && hoe < 0) return "Hotbara çapa koy! (Çapa zorunluluğu açık)";
        if (cfg.requireFortune && (hoe < 0 || fortune(inv.getStack(hoe)) < 3)) {
            return "Hotbarda Servet III alet olmalı! (Servet III zorunluluğu açık)";
        }

        // Çapa ele alınmamışsa: önce ele al, sonraki tick'te kır (sunucu senkronu için)
        if (hoe >= 0 && inv.selectedSlot != hoe) {
            inv.selectedSlot = hoe;
            cooldown = 0;
            return null;
        }

        im.attackBlock(hit.getBlockPos(), hit.getSide());
        p.swingHand(Hand.MAIN_HAND);
        cooldown = gap - 1;
        return hoe < 0 ? "Hotbarda çapa yok! (elle kırılıyor)" : null;
    }

    // ---------------------------------------------------------------- kemik tozu

    private static String boneMeal(ClientPlayerEntity p, ClientPlayerInteractionManager im,
                                   BlockHitResult hit, int gap) {
        PlayerInventory inv = p.getInventory();
        boolean inHand = p.getMainHandStack().isOf(Items.BONE_MEAL) || p.getOffHandStack().isOf(Items.BONE_MEAL);

        if (!inHand) {
            int bone = -1;
            for (int i = 0; i < 9; i++) {
                if (inv.getStack(i).isOf(Items.BONE_MEAL)) {
                    bone = i;
                    break;
                }
            }
            if (bone < 0) return "Kemik tozu yok! (hotbara ya da 2. ele koy)";
            inv.selectedSlot = bone;
            cooldown = 0;
            return null;
        }

        use(p, im, hit);
        cooldown = gap - 1;
        return null;
    }

    // ---------------------------------------------------------------- ek

    private static String plant(ClientPlayerEntity p, ClientPlayerInteractionManager im,
                                AutoFarmConfig cfg, BlockHitResult hit, int gap) {
        PlayerInventory inv = p.getInventory();
        ItemStack main = p.getMainHandStack();
        ItemStack off = p.getOffHandStack();

        if (isUsableSeed(off, cfg) || isUsableSeed(main, cfg)) {
            use(p, im, hit);
            cooldown = gap - 1;
            return null;
        }

        // 2. el boşsa ekini çantadan 2. ele al (oyuncunun F / envanter takası gibi), sonraki tick'te ek
        if (off.isEmpty()) {
            int from = -1;
            for (int i = 0; i < 36; i++) {
                if (isUsableSeed(inv.getStack(i), cfg)) {
                    from = i;
                    break;
                }
            }
            if (from < 0) return "Çantada ekin bulunamadı!";
            int slot = from < 9 ? from + 36 : from;
            im.clickSlot(p.playerScreenHandler.syncId, slot, 40, SlotActionType.SWAP, p);
            cooldown = 1;
            return null;
        }

        // 2. el başka bir şeyle dolu: ekini hotbardan ele al
        for (int i = 0; i < 9; i++) {
            if (isUsableSeed(inv.getStack(i), cfg)) {
                inv.selectedSlot = i;
                cooldown = 0;
                return null;
            }
        }
        return "Ekin yok! (2. eli boşalt ya da hotbara ekin koy)";
    }

    // ---------------------------------------------------------------- yardımcılar

    /** Vanilla sağ tık gibi: önce ana el, kabul edilmezse 2. el. Gerçek bakış sonucuyla. */
    private static void use(ClientPlayerEntity p, ClientPlayerInteractionManager im, BlockHitResult hit) {
        for (Hand hand : Hand.values()) {
            if (p.getStackInHand(hand).isEmpty()) continue;
            ActionResult r = im.interactBlock(p, hand, hit);
            if (r.isAccepted()) {
                p.swingHand(hand);
                return;
            }
        }
    }

    private static int bestHoeSlot(PlayerInventory inv) {
        int best = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.getStack(i);
            if (s.isIn(ItemTags.HOES) && (best < 0 || fortune(s) > fortune(inv.getStack(best)))) best = i;
        }
        return best;
    }

    private static boolean isUsableSeed(ItemStack s, AutoFarmConfig cfg) {
        return !s.isEmpty() && s.getItem() instanceof BlockItem bi
                && bi.getBlock() instanceof CropBlock && cfg.isSelected(bi.getBlock());
    }

    private static int fortune(ItemStack stack) {
        ItemEnchantmentsComponent ench = stack.getEnchantments();
        for (RegistryEntry<Enchantment> e : ench.getEnchantments()) {
            if (e.matchesKey(Enchantments.FORTUNE)) return ench.getLevel(e);
        }
        return 0;
    }
}
