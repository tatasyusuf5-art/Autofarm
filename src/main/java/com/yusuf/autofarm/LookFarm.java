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
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * Mod 4: sadece baktığın bloğa çalışır. Yürümez, bakış çevirmez.
 * Her tick: olgunsa kır -> boşsa ek -> kemik tozu bas.
 *
 * Sunucu atmasın diye paket bütçesi var: saniyede kullanılabilecek paket sayısı
 * hız ayarına bağlı (16 + hız * 8). Bütçe dolarsa o tick beklenir, sonraki tick devam eder.
 * Kötü bir şey olmaz: her tick'te durum dünyadan yeniden okunur.
 *
 * Yerleşim (önerilen): ekin (havuç vs.) 2. elde, hotbarda çapa + kemik tozu.
 * 2. el boşsa ekin otomatik 2. ele alınır. Ekin 2. elde değilse hotbardan slot değiştirerek ekilir.
 */
public final class LookFarm {
    /** Son 20 tick'te kullanılan paket sayısı (yaklaşık 1 saniye). */
    private static final int[] WINDOW = new int[20];
    private static int widx = 0;
    private static int left = 0;
    private static int serverSlot = -1;

    private LookFarm() {
    }

    private static boolean spend(int n) {
        if (n > left) return false;
        left -= n;
        WINDOW[widx] += n;
        return true;
    }

    /** @return ekranda gösterilecek uyarı, sorun yoksa null */
    public static String tick(MinecraftClient mc, AutoFarmConfig cfg) {
        // paket penceresini ilerlet (boş tick'lerde de eskir)
        widx = (widx + 1) % WINDOW.length;
        WINDOW[widx] = 0;
        int used = 0;
        for (int v : WINDOW) used += v;
        left = (16 + cfg.speed * 8) - used;

        ClientPlayerEntity p = mc.player;
        ClientWorld w = mc.world;
        ClientPlayerInteractionManager im = mc.interactionManager;
        if (p == null || w == null || im == null) return null;
        if (left < 2) return null;

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

        PlayerInventory inv = p.getInventory();
        // Sunucudaki slot = bizim son seçtiğimiz slot (oyun her tick başında senkronluyor)
        serverSlot = inv.selectedSlot;

        // Hotbar taraması: çapa (en yüksek Servet) ve kemik tozu
        int hoe = -1;
        int bone = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.getStack(i);
            if (bone < 0 && s.isOf(Items.BONE_MEAL)) bone = i;
            if (s.isIn(ItemTags.HOES) && (hoe < 0 || fortune(s) > fortune(inv.getStack(hoe)))) hoe = i;
        }
        boolean offBone = p.getOffHandStack().isOf(Items.BONE_MEAL);

        String notice = hoe < 0 ? "Hotbarda çapa yok! (elle kırılıyor)" : null;
        boolean swing = false;
        boolean planted = false;
        Block brokenBlock = null;

        // 1) Olgunsa kır
        if (hasCrop && ((CropBlock) cs.getBlock()).isMature(cs)) {
            if (cfg.requireHoe && hoe < 0) return "Hotbara çapa koy! (Çapa zorunluluğu açık)";
            if (cfg.requireFortune && (hoe < 0 || fortune(inv.getStack(hoe)) < 3)) {
                return "Hotbarda Servet III alet olmalı! (Servet III zorunluluğu açık)";
            }
            int cost = 1 + (hoe >= 0 && serverSlot != hoe ? 2 : 0);
            if (!spend(cost)) return notice;
            if (hoe >= 0) select(p, inv, hoe);
            im.attackBlock(crop, Direction.UP);
            swing = true;
            brokenBlock = cs.getBlock();
            hasCrop = false;
        }

        // 2) Boşsa ek (aynı tick'te, kırdıktan hemen sonra)
        if (!hasCrop) {
            Hand hand = null;
            int seedSlot = -1;
            ItemStack off = p.getOffHandStack();

            if (!offBone && isUsableSeed(off, cfg)) {
                hand = Hand.OFF_HAND;
            } else if (!offBone && off.isEmpty()) {
                // 2. el boş: ekini çantadan 2. ele al, ekim sonraki tick'te olur
                int from = findSeedInInventory(inv, cfg);
                if (from < 0) return flush(p, swing, "Çantada ekin bulunamadı!");
                if (spend(1)) {
                    int slot = from < 9 ? from + 36 : from;
                    im.clickSlot(p.playerScreenHandler.syncId, slot, 40, SlotActionType.SWAP, p);
                }
                return flush(p, swing, notice);
            } else {
                seedSlot = findSeedInHotbar(inv, cfg, brokenBlock);
                if (seedSlot < 0) return flush(p, swing, "Ekin yok! (2. ele ya da hotbara koy)");
                hand = Hand.MAIN_HAND;
            }

            int cost = 1 + (hand == Hand.MAIN_HAND && serverSlot != seedSlot ? 2 : 0);
            if (!spend(cost)) return flush(p, swing, notice);
            if (hand == Hand.MAIN_HAND) select(p, inv, seedSlot);
            BlockHitResult place = new BlockHitResult(
                    Vec3d.ofCenter(farm).add(0.0, 0.4375, 0.0), Direction.UP, farm, false);
            ActionResult r = im.interactBlock(p, hand, place);
            if (hand == Hand.MAIN_HAND) serverSlot = seedSlot;
            if (!r.isAccepted()) return flush(p, swing, notice);
            swing = true;
            planted = true;
        }

        // 3) Kemik tozu: yeni ektiysek 2 kez (kesin gerekli), sonraki tick'lerde 1 kez
        if (bone < 0 && !offBone) return flush(p, swing, "Kemik tozu yok! (hotbara ya da 2. ele koy)");
        int n = planted ? 2 : 1;
        Hand boneHand = offBone ? Hand.OFF_HAND : Hand.MAIN_HAND;
        int cost = n + (!offBone && serverSlot != bone ? 2 : 0);
        if (!spend(cost)) return flush(p, swing, notice);
        if (!offBone) select(p, inv, bone);
        BlockHitResult bh = new BlockHitResult(Vec3d.ofCenter(crop), Direction.UP, crop, false);
        for (int i = 0; i < n; i++) {
            im.interactBlock(p, boneHand, bh);
        }
        if (!offBone) serverSlot = bone;
        return flush(p, true, notice);
    }

    /** Tick başına tek el sallama (paket tasarrufu). */
    private static String flush(ClientPlayerEntity p, boolean swing, String notice) {
        if (swing && spend(1)) p.swingHand(Hand.MAIN_HAND);
        return notice;
    }

    /** Slotu seç ve sunucuya bildir. attackBlock slotu kendisi senkronlamıyor, bu yüzden elle gönderiyoruz. */
    private static void select(ClientPlayerEntity p, PlayerInventory inv, int slot) {
        inv.selectedSlot = slot;
        if (serverSlot != slot) {
            p.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(slot));
            serverSlot = slot;
        }
    }

    private static boolean isUsableSeed(ItemStack s, AutoFarmConfig cfg) {
        return !s.isEmpty() && s.getItem() instanceof BlockItem bi
                && bi.getBlock() instanceof CropBlock && cfg.isSelected(bi.getBlock());
    }

    private static int findSeedInInventory(PlayerInventory inv, AutoFarmConfig cfg) {
        for (int i = 0; i < 36; i++) {
            if (isUsableSeed(inv.getStack(i), cfg)) return i;
        }
        return -1;
    }

    /** Hotbarda seçili ekinlerden birini bul; kırdığımız ekinle aynı olanı tercih et. */
    private static int findSeedInHotbar(PlayerInventory inv, AutoFarmConfig cfg, Block preferred) {
        int any = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.getStack(i);
            if (!isUsableSeed(s, cfg)) continue;
            if (((BlockItem) s.getItem()).getBlock() == preferred) return i;
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
