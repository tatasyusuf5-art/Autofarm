package com.yusuf.autofarm;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.block.NetherWartBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

public class AutoFarmClient implements ClientModInitializer {
   /** Etkileşim mesafesi (göz -> hedef nokta). Vanilla sınırı 4.5. */
   private static final double REACH = 4.0;
   /** Bir istasyonun kapsadığı yarıçap (5x5 hücre). */
   private static final int STATION_RADIUS = 2;
   private static final String[] MODE_NAMES = new String[]{
      "1: Kır (olgun ekinleri kırar)",
      "2: Ek (boş tarlaları eker)",
      "3: Kır + Ek (kırar ve geri eker)",
      "4: Kemik tozu (baktığın blok: ek + toz + kır)"
   };
   private static final int[][] DIRS = new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
   private final AutoFarmConfig cfg = AutoFarmConfig.get();
   private boolean enabled = false;
   private boolean openGui = false;
   private boolean prevG = false;
   private boolean announce = true;

   // Alansız (eski) mod için hedef
   private BlockPos targetPos;
   private AutoFarmClient.Kind targetKind;
   private int targetTicks;
   private int actionCooldown;
   private int tickCounter;
   private int warnCooldown;
   private boolean keysHeld;

   // Yol bulma
   private List<BlockPos> path;
   private int pathIdx;
   private int replans;
   private double approach = 0.3;

   // Dönüş sabitlendi mi (aksiyondan önce rotasyon sunucuya gitmiş olmalı)
   private float lastYaw;
   private float lastPitch;
   private boolean haveLast;

   // Alan / rota
   private final List<BlockPos> stations = new ArrayList<>();
   private final List<Integer> layers = new ArrayList<>();
   private final Set<Integer> layerSet = new HashSet<>();
   private boolean pathWarned;
   private int stationIdx;
   private boolean routeBuilt;
   private boolean finished;
   private int idleScan;
   private int blockedTicks;

   private final Map<BlockPos, Integer> blacklist = new HashMap<>();
   private final Map<BlockPos, Integer> replant = new HashMap<>();

   public void onInitializeClient() {
      this.cfg.load();
      ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
      ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
         ClientCommandManager.literal("autofarm")
            .executes(ctx -> {
               this.toggle(MinecraftClient.getInstance());
               return 1;
            })
            .then(ClientCommandManager.literal("gui").executes(ctx -> {
               this.openGui = true;
               return 1;
            }))
            .then(ClientCommandManager.literal("pos1").executes(ctx -> {
               this.setCorner(MinecraftClient.getInstance(), 1);
               return 1;
            }))
            .then(ClientCommandManager.literal("pos2").executes(ctx -> {
               this.setCorner(MinecraftClient.getInstance(), 2);
               return 1;
            }))
            .then(ClientCommandManager.literal("area")
               .executes(ctx -> {
                  this.chat(MinecraftClient.getInstance(), this.areaInfo());
                  return 1;
               })
               .then(ClientCommandManager.literal("sil").executes(ctx -> {
                  this.clearArea();
                  this.chat(MinecraftClient.getInstance(), "Alan silindi.");
                  return 1;
               })))
            .then(ClientCommandManager.literal("mode").then(
               ClientCommandManager.argument("n", IntegerArgumentType.integer(1, MODE_NAMES.length)).executes(ctx -> {
                  this.cfg.mode = IntegerArgumentType.getInteger(ctx, "n") - 1;
                  this.cfg.save();
                  this.resetState(MinecraftClient.getInstance());
                  this.say(MinecraftClient.getInstance(), "Mod -> " + MODE_NAMES[this.cfg.mode]);
                  return 1;
               })))
            .then(ClientCommandManager.literal("speed").then(
               ClientCommandManager.argument("n", IntegerArgumentType.integer(1, 15)).executes(ctx -> {
                  this.cfg.speed = IntegerArgumentType.getInteger(ctx, "n");
                  this.cfg.save();
                  this.say(MinecraftClient.getInstance(), "Hız -> " + this.cfg.speed + "/15");
                  return 1;
               })))
      ));
   }

   public boolean isEnabled() {
      return this.enabled;
   }

   public void toggle(MinecraftClient mc) {
      this.enabled = !this.enabled;
      this.resetState(mc);
      String msg = "AutoFarm " + (this.enabled ? "AÇIK" : "KAPALI") + " | Mod " + MODE_NAMES[this.cfg.mode]
         + (this.useArea() ? " | Alan: " + this.areaSize() : "");
      this.say(mc, msg);
      this.chat(mc, "[AutoFarm] " + msg);
   }

   // ------------------------------------------------------------------ alan seçimi

   /** Baktığın bloğu köşe olarak kaydeder (1 veya 2). Ekine bakıyorsan altındaki toprak katı alınır. */
   public void setCorner(MinecraftClient mc, int idx) {
      if (mc.player == null || mc.world == null) {
         return;
      }

      HitResult hr = mc.player.raycast(64.0, 1.0F, false);
      if (!(hr instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
         this.chat(mc, "[AutoFarm] Bir bloğa bak (en fazla 64 blok).");
         return;
      }

      BlockPos bp = hit.getBlockPos();
      Block b = mc.world.getBlockState(bp).getBlock();
      int y = b instanceof CropBlock || b instanceof NetherWartBlock ? bp.getY() - 1 : bp.getY();
      if (idx == 1) {
         this.cfg.hasP1 = true;
         this.cfg.p1x = bp.getX();
         this.cfg.p1y = y;
         this.cfg.p1z = bp.getZ();
      } else {
         this.cfg.hasP2 = true;
         this.cfg.p2x = bp.getX();
         this.cfg.p2y = y;
         this.cfg.p2z = bp.getZ();
      }

      this.cfg.save();
      this.routeBuilt = false;
      this.chat(mc, "[AutoFarm] Köşe " + idx + " = " + bp.getX() + ", " + y + ", " + bp.getZ() + "  " + this.areaInfo());
      if (this.cfg.hasArea() && this.cfg.p1y != this.cfg.p2y) {
         this.chat(mc, "[AutoFarm] Çok katlı farm: Y " + this.cfg.minY() + ".." + this.cfg.maxY() + " arasındaki tüm ekin katları kullanılacak.");
      }
   }

   public void clearArea() {
      this.cfg.clearArea();
      this.cfg.save();
      this.routeBuilt = false;
      this.stations.clear();
   }

   private String areaInfo() {
      if (!this.cfg.hasArea()) {
         return "Alan seçili değil (köşe 1: " + (this.cfg.hasP1 ? "var" : "yok") + ", köşe 2: " + (this.cfg.hasP2 ? "var" : "yok") + ")";
      }

      return "Alan: " + this.areaSize() + " | X " + this.cfg.minX() + ".." + this.cfg.maxX()
         + " Z " + this.cfg.minZ() + ".." + this.cfg.maxZ() + " Y " + this.cfg.minY() + ".." + this.cfg.maxY();
   }

   private String areaSize() {
      return (this.cfg.maxX() - this.cfg.minX() + 1) + "x" + (this.cfg.maxZ() - this.cfg.minZ() + 1);
   }

   private boolean useArea() {
      return this.cfg.useArea && this.cfg.hasArea();
   }

   private void resetState(MinecraftClient mc) {
      this.clearTarget();
      this.blacklist.clear();
      this.replant.clear();
      this.routeBuilt = false;
      this.finished = false;
      this.stations.clear();
      this.stationIdx = 0;
      this.blockedTicks = 0;
      this.haveLast = false;
      this.releaseKeys(mc);
   }

   private void say(MinecraftClient mc, String msg) {
      if (mc.player != null) {
         mc.player.sendMessage(Text.literal(msg), true);
      }
   }

   private void chat(MinecraftClient mc, String msg) {
      if (mc.player != null) {
         mc.player.sendMessage(Text.literal(msg), false);
      }
   }

   private void warn(MinecraftClient mc, String msg) {
      if (this.warnCooldown == 0) {
         this.say(mc, msg);
         this.warnCooldown = 40;
      }
   }

   private float lookStep() {
      return 9.0F * this.cfg.speed;
   }

   private int actionDelay() {
      return this.cfg.speed >= 10 ? 0 : (this.cfg.speed >= 7 ? 1 : (this.cfg.speed >= 4 ? 2 : 3));
   }

   // ------------------------------------------------------------------ ana tick

   private void onTick(MinecraftClient mc) {
      this.tickCounter++;
      if (this.announce && mc.player != null && mc.world != null) {
         this.announce = false;
         this.chat(mc, "[AutoFarm] Yüklendi. G = aç/kapat, R + G = menü, /autofarm gui | alan: /autofarm pos1, pos2");
      }

      boolean g = false;
      boolean r = false;
      if (mc.currentScreen == null && mc.player != null) {
         long window = mc.getWindow().getHandle();
         g = InputUtil.isKeyPressed(window, 71);
         r = InputUtil.isKeyPressed(window, 82);
      }

      if (g && !this.prevG) {
         if (r) {
            this.openGui = true;
         } else {
            this.toggle(mc);
         }
      }

      this.prevG = g;
      if (this.openGui && mc.player != null && mc.currentScreen == null) {
         this.openGui = false;
         this.releaseKeys(mc);
         mc.setScreen(new AutoFarmScreen(this));
         return;
      }

      if (this.warnCooldown > 0) {
         this.warnCooldown--;
      }

      if (this.actionCooldown > 0) {
         this.actionCooldown--;
      }

      if (!this.enabled || mc.player == null || mc.world == null || mc.interactionManager == null
         || mc.currentScreen != null || mc.player.isSpectator()) {
         this.releaseKeys(mc);
         return;
      }

      if (this.tickCounter % 40 == 0) {
         this.blacklist.values().removeIf(t -> t < this.tickCounter);
         this.replant.values().removeIf(t -> t < this.tickCounter);
      }

      // Mod 4: baktığın blok
      if (this.cfg.mode == 3) {
         this.releaseKeys(mc);
         String lookMsg = LookFarm.tick(mc, this.cfg);
         if (lookMsg != null) {
            this.warn(mc, lookMsg);
         }

         return;
      }

      boolean harvestMode = this.cfg.mode == 0 || this.cfg.mode == 2;
      boolean plantMode = this.cfg.mode == 1 || this.cfg.mode == 2;
      if (harvestMode) {
         ItemStack tool = mc.player.getMainHandStack();
         if (this.cfg.requireHoe && !tool.isIn(ItemTags.HOES)) {
            this.warn(mc, "Ana ele çapa al! (Çapa zorunluluğu açık)");
            this.releaseKeys(mc);
            return;
         }

         if (this.cfg.requireFortune && fortuneLevel(tool) < 3) {
            this.warn(mc, "Ana elde Servet III alet olmalı! (Servet III zorunluluğu açık)");
            this.releaseKeys(mc);
            return;
         }
      }

      Hand plantHand = null;
      Block plantBlock = null;
      if (plantMode) {
         plantHand = this.findPlantHand(mc, this.cfg.mode == 1);
         if (plantHand == null) {
            this.supply(mc);
            this.releaseKeys(mc);
            return;
         }

         plantBlock = ((BlockItem)mc.player.getStackInHand(plantHand).getItem()).getBlock();
      }

      if (this.useArea()) {
         this.areaTick(mc, plantHand, plantBlock, harvestMode);
         return;
      }

      // ---- alansız (eski) davranış: çevrede en yakın hedef
      if (this.targetKind != null && !this.targetStillValid(mc, plantBlock)) {
         this.clearTarget();
      }

      if (this.targetKind == null) {
         this.findTarget(mc, plantBlock);
         if (this.targetKind == null) {
            this.releaseKeys(mc);
            return;
         }
      }

      if (++this.targetTicks > 400) {
         this.releaseKeys(mc);
         this.giveUp();
      } else {
         this.step(mc, plantHand, plantBlock);
      }
   }

   // ------------------------------------------------------------------ ALAN MODU (çok katlı)

   private boolean inArea(BlockPos pos) {
      return this.layerSet.contains(pos.getY()) && this.cfg.contains(pos.getX(), pos.getZ());
   }

   private static boolean isGround(Block b) {
      return b == Blocks.FARMLAND || b == Blocks.SOUL_SAND;
   }

   /** Alanın Y aralığındaki ekin katlarını bulur: içinde en az 2 çiftlik toprağı (ya da ruh kumu) olan katlar. */
   private void detectLayers(MinecraftClient mc) {
      this.layers.clear();
      this.layerSet.clear();
      for (int y = this.cfg.minY(); y <= this.cfg.maxY(); y++) {
         int count = 0;
         for (int x = this.cfg.minX(); x <= this.cfg.maxX() && count < 2; x++) {
            for (int z = this.cfg.minZ(); z <= this.cfg.maxZ() && count < 2; z++) {
               if (isGround(mc.world.getBlockState(new BlockPos(x, y, z)).getBlock())) {
                  count++;
               }
            }
         }

         if (count >= 2) {
            this.layers.add(y + 1);
            this.layerSet.add(y + 1);
         }
      }
   }

   private AutoFarmClient.Kind cellKind(MinecraftClient mc, BlockPos pos, boolean harvest, Block plantBlock) {
      BlockState s = mc.world.getBlockState(pos);
      if (harvest && isMature(s) && this.cfg.isSelected(s.getBlock())) {
         return AutoFarmClient.Kind.HARVEST;
      }

      if (plantBlock != null && s.isAir() && plantBlock.getDefaultState().canPlaceAt(mc.world, pos)) {
         return AutoFarmClient.Kind.PLANT;
      }

      return null;
   }

   private void areaTick(MinecraftClient mc, Hand plantHand, Block plantBlock, boolean harvest) {
      // Her tick: erişimdeki en uygun işi yap VE (varsa) rotada ilerlemeye devam et (koşarken kır/ek)
      AutoFarmClient.Cell cell = this.bestReachable(mc, harvest, plantBlock);
      if (cell != null) {
         this.workOn(mc, cell, plantHand, plantBlock, harvest);
      } else {
         this.blockedTicks = 0;
      }

      this.routeStep(mc, harvest, plantBlock, cell != null);
   }

   /** Erişimdeki en uygun hücre: en az bakış dönüşü + yakınlık, ve arada engel olmayan (ışın hedefe değiyor). */
   private AutoFarmClient.Cell bestReachable(MinecraftClient mc, boolean harvest, Block plantBlock) {
      ClientPlayerEntity p = mc.player;
      Vec3d eye = p.getEyePos();
      BlockPos feet = p.getBlockPos();
      AutoFarmClient.Cell best = null;
      double bestScore = Double.MAX_VALUE;

      for (int ly : this.layers) {
         if (Math.abs(ly - feet.getY()) > 6) {
            continue;
         }

         for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
               BlockPos pos = new BlockPos(feet.getX() + dx, ly, feet.getZ() + dz);
               if (!this.cfg.contains(pos.getX(), pos.getZ()) || this.blacklist.containsKey(pos)) {
                  continue;
               }

               AutoFarmClient.Kind k = this.cellKind(mc, pos, harvest, plantBlock);
               if (k == null) {
                  continue;
               }

               Vec3d aim = aimPoint(pos);
               double d = eye.distanceTo(aim);
               if (d > REACH) {
                  continue;
               }

               double score = this.lookError(p, aim) + d * 3.0;
               if (score >= bestScore || !this.clearView(mc, eye, aim, pos, k)) {
                  continue;
               }

               bestScore = score;
               best = new AutoFarmClient.Cell(pos, k);
            }
         }
      }

      return best;
   }

   /** Gözden hedef noktaya çekilen ışın gerçekten hedef bloğa mı çarpıyor? (kat tavanı, duvar, yüksek ekin vb. engeli) */
   private boolean clearView(MinecraftClient mc, Vec3d eye, Vec3d aim, BlockPos pos, AutoFarmClient.Kind k) {
      Vec3d dir = aim.subtract(eye);
      double len = dir.length();
      if (len < 1.0E-4) {
         return false;
      }

      Vec3d end = aim.add(dir.multiply(0.6 / len));
      BlockHitResult r = mc.world.raycast(new RaycastContext(eye, end, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, mc.player));
      if (r.getType() != HitResult.Type.BLOCK) {
         return false;
      }

      if (k == AutoFarmClient.Kind.HARVEST) {
         return r.getBlockPos().equals(pos);
      }

      return r.getBlockPos().equals(pos.down()) && r.getSide() == Direction.UP;
   }

   private void workOn(MinecraftClient mc, AutoFarmClient.Cell cell, Hand plantHand, Block plantBlock, boolean harvest) {
      ClientPlayerEntity p = mc.player;
      ClientWorld w = mc.world;
      BlockPos tp = cell.pos();
      AutoFarmClient.Kind tk = cell.kind();
      Vec3d aim = aimPoint(tp);
      this.lookAt(p, aim);
      boolean settled = this.settled(p);

      BlockHitResult hit = null;
      boolean ok = false;
      HitResult hr = p.raycast(p.getBlockInteractionRange(), 1.0F, false);
      if (hr instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK) {
         hit = b;
         if (tk == AutoFarmClient.Kind.HARVEST) {
            ok = b.getBlockPos().equals(tp);
         } else {
            ok = b.getBlockPos().equals(tp.down()) && b.getSide() == Direction.UP;
         }

         if (!ok) {
            // Bakış başka bir yararlı bloğa denk geldiyse onu yap (oyuncu da öyle yapardı)
            BlockPos hp = b.getBlockPos();
            BlockState hs = w.getBlockState(hp);
            if (harvest && this.inArea(hp) && isMature(hs) && this.cfg.isSelected(hs.getBlock()) && !this.blacklist.containsKey(hp)) {
               tp = hp.toImmutable();
               tk = AutoFarmClient.Kind.HARVEST;
               ok = true;
            } else if (plantBlock != null && b.getSide() == Direction.UP) {
               BlockPos above = hp.up();
               if (this.inArea(above) && w.getBlockState(above).isAir() && !this.blacklist.containsKey(above)
                  && plantBlock.getDefaultState().canPlaceAt(w, above)) {
                  tp = above.toImmutable();
                  tk = AutoFarmClient.Kind.PLANT;
                  ok = true;
               }
            }
         }
      }

      if (!ok) {
         // Dönüş bittiği halde ışın hedefe değmiyorsa: önünde engel var. Bir süre sonra bu hücreyi atla.
         if (settled && ++this.blockedTicks > 6) {
            this.blacklist.put(cell.pos().toImmutable(), this.tickCounter + 200);
            this.blockedTicks = 0;
         }

         return;
      }

      this.blockedTicks = 0;
      if (!settled || this.actionCooldown > 0) {
         return;
      }

      if (tk == AutoFarmClient.Kind.HARVEST) {
         mc.interactionManager.attackBlock(tp, hit.getSide());
         p.swingHand(Hand.MAIN_HAND);
      } else {
         ActionResult res = mc.interactionManager.interactBlock(p, plantHand, hit);
         if (res.isAccepted()) {
            p.swingHand(plantHand);
         } else {
            this.blacklist.put(tp.toImmutable(), this.tickCounter + 100);
         }
      }

      this.actionCooldown = this.actionDelay();
      this.preAim(mc, harvest, plantBlock);
   }

   /**
    * Eylemden hemen sonra sıradaki hedefe dön. Dönüş bir sonraki tick'in başında sunucuya gider,
    * eylem o tick'te yapılır: böylece her tick bir eylem yapılabilir, dönüş için ekstra tick harcanmaz.
    */
   private void preAim(MinecraftClient mc, boolean harvest, Block plantBlock) {
      AutoFarmClient.Cell next = this.bestReachable(mc, harvest, plantBlock);
      if (next == null) {
         return;
      }

      ClientPlayerEntity p = mc.player;
      this.lookAt(p, aimPoint(next.pos()));
      this.lastYaw = p.getYaw();
      this.lastPitch = p.getPitch();
      this.haveLast = true;
   }

   /** İstasyonları sırayla gez (iş yaparken de yürümeye devam et); rota bitince eksik kalan yer var mı tara. */
   private void routeStep(MinecraftClient mc, boolean harvest, Block plantBlock, boolean workInReach) {
      ClientPlayerEntity p = mc.player;
      if (!this.routeBuilt) {
         this.buildRoute(mc);
         this.routeBuilt = true;
         this.stationIdx = 0;
         this.path = null;
         this.replans = 0;
         this.finished = false;
      }

      while (this.stationIdx < this.stations.size()) {
         BlockPos st = this.stations.get(this.stationIdx);
         if (!this.stationHasWork(mc, st, harvest, plantBlock)) {
            this.nextStation();
            continue;
         }

         if (this.arrived(p, st)) {
            if (workInReach) {
               // istasyonun işi bitene kadar dur
               this.releaseKeys(mc);
               return;
            }

            this.nextStation();
            continue;
         }

         if (!this.navigate(mc, Vec3d.ofCenter(st), AutoFarmClient.Kind.STATION, 0.6)) {
            this.releaseKeys(mc);
            if (!this.pathWarned) {
               this.pathWarned = true;
               this.chat(mc, "[AutoFarm] Bir istasyona yol bulunamadı (" + st.getX() + ", " + st.getY() + ", " + st.getZ()
                  + "). Katlar arası geçiş (merdiven/rampa) var mı? Atlıyorum.");
            }

            this.nextStation();
            continue;
         }

         return;
      }

      // Rota bitti
      if (workInReach) {
         this.releaseKeys(mc);
         return;
      }

      if (this.finished) {
         this.releaseKeys(mc);
         if (++this.idleScan < 20) {
            return;
         }

         this.idleScan = 0;
         if (this.nearestActionable(mc, harvest, plantBlock) != null) {
            this.finished = false;
            this.routeBuilt = false;
         }

         return;
      }

      // Kontrol turu: eksik ekilmemiş / olgun yer kaldı mı
      AutoFarmClient.Cell left = this.nearestActionable(mc, harvest, plantBlock);
      if (left != null) {
         if (!this.navigate(mc, aimPoint(left.pos()), AutoFarmClient.Kind.HARVEST, 3.0)) {
            this.releaseKeys(mc);
            this.blacklist.put(left.pos().toImmutable(), this.tickCounter + 600);
            this.path = null;
            this.replans = 0;
         }

         return;
      }

      this.releaseKeys(mc);
      this.finished = true;
      this.idleScan = 0;
      this.chat(mc, "[AutoFarm] Tur tamam: alanda ekilmemiş ya da olgun yer kalmadı. Yeni iş çıkınca devam eder.");
   }

   private void nextStation() {
      this.stationIdx++;
      this.path = null;
      this.replans = 0;
   }

   private boolean arrived(ClientPlayerEntity p, BlockPos st) {
      return Math.hypot(p.getX() - (st.getX() + 0.5), p.getZ() - (st.getZ() + 0.5)) < 0.8
         && Math.abs(p.getY() - st.getY()) < 1.5;
   }

   private boolean stationHasWork(MinecraftClient mc, BlockPos st, boolean harvest, Block plantBlock) {
      for (int dx = -STATION_RADIUS; dx <= STATION_RADIUS; dx++) {
         for (int dz = -STATION_RADIUS; dz <= STATION_RADIUS; dz++) {
            BlockPos pos = new BlockPos(st.getX() + dx, st.getY(), st.getZ() + dz);
            if (this.cfg.contains(pos.getX(), pos.getZ()) && !this.blacklist.containsKey(pos)
               && this.cellKind(mc, pos, harvest, plantBlock) != null) {
               return true;
            }
         }
      }

      return false;
   }

   private AutoFarmClient.Cell nearestActionable(MinecraftClient mc, boolean harvest, Block plantBlock) {
      ClientPlayerEntity p = mc.player;
      AutoFarmClient.Cell best = null;
      double bd = Double.MAX_VALUE;
      for (int ly : this.layers) {
         for (int x = this.cfg.minX(); x <= this.cfg.maxX(); x++) {
            for (int z = this.cfg.minZ(); z <= this.cfg.maxZ(); z++) {
               BlockPos pos = new BlockPos(x, ly, z);
               if (this.blacklist.containsKey(pos)) {
                  continue;
               }

               AutoFarmClient.Kind k = this.cellKind(mc, pos, harvest, plantBlock);
               if (k == null) {
                  continue;
               }

               double d = Math.hypot(x + 0.5 - p.getX(), z + 0.5 - p.getZ()) + Math.abs(ly - p.getY()) * 3.0;
               if (d < bd) {
                  bd = d;
                  best = new AutoFarmClient.Cell(pos, k);
               }
            }
         }
      }

      return best;
   }

   // ------------------------------------------------------------------ rota çıkarma

   /** Katları oyuncuya en yakından başlayarak sırala, her katı yılan (zikzak) rotayla dizip birleştir. */
   private void buildRoute(MinecraftClient mc) {
      this.stations.clear();
      this.pathWarned = false;
      this.detectLayers(mc);
      if (this.layers.isEmpty()) {
         this.chat(mc, "[AutoFarm] Alanda çiftlik toprağı bulunamadı. Köşeleri toprağın üstüne bakarak seç.");
         return;
      }

      BlockPos pf = mc.player.getBlockPos();
      List<Integer> pending = new ArrayList<>(this.layers);
      List<Integer> order = new ArrayList<>();
      int curY = pf.getY();
      while (!pending.isEmpty()) {
         int bestL = pending.get(0);
         for (int ly : pending) {
            if (Math.abs(ly - curY) < Math.abs(bestL - curY)) {
               bestL = ly;
            }
         }

         pending.remove(Integer.valueOf(bestL));
         order.add(bestL);
         curY = bestL;
      }

      BlockPos from = pf;
      for (int ly : order) {
         List<BlockPos> route = this.layerRoute(mc, ly, from);
         if (!route.isEmpty()) {
            this.stations.addAll(route);
            from = route.get(route.size() - 1);
         }
      }
   }

   /** Tek kat için: 5x5'lik istasyonlar, yılan sırası, bir önceki konuma en yakın köşeden başla. */
   private List<BlockPos> layerRoute(MinecraftClient mc, int ly, BlockPos from) {
      List<Integer> xs = centers(this.cfg.minX(), this.cfg.maxX());
      List<Integer> zs = centers(this.cfg.minZ(), this.cfg.maxZ());
      boolean rowsAlongX = this.cfg.maxX() - this.cfg.minX() >= this.cfg.maxZ() - this.cfg.minZ();
      List<BlockPos> bestRoute = new ArrayList<>();
      double bestDist = Double.MAX_VALUE;

      for (int variant = 0; variant < 4; variant++) {
         List<Integer> vx = new ArrayList<>(xs);
         List<Integer> vz = new ArrayList<>(zs);
         if ((variant & 1) != 0) {
            Collections.reverse(vx);
         }

         if ((variant & 2) != 0) {
            Collections.reverse(vz);
         }

         List<BlockPos> route = new ArrayList<>();
         if (rowsAlongX) {
            for (int zi = 0; zi < vz.size(); zi++) {
               for (int k = 0; k < vx.size(); k++) {
                  int xi = zi % 2 == 0 ? k : vx.size() - 1 - k;
                  this.addStation(mc, route, vx.get(xi), vz.get(zi), ly);
               }
            }
         } else {
            for (int xi = 0; xi < vx.size(); xi++) {
               for (int k = 0; k < vz.size(); k++) {
                  int zi = xi % 2 == 0 ? k : vz.size() - 1 - k;
                  this.addStation(mc, route, vx.get(xi), vz.get(zi), ly);
               }
            }
         }

         if (route.isEmpty()) {
            continue;
         }

         double d = Math.hypot(route.get(0).getX() - from.getX(), route.get(0).getZ() - from.getZ());
         if (d < bestDist) {
            bestDist = d;
            bestRoute = route;
         }
      }

      return bestRoute;
   }

   /** İstasyon merkezi durulabilir değilse (su, çit vb.) yakındaki en yakın durulabilir hücreyi seçer. */
   private void addStation(MinecraftClient mc, List<BlockPos> route, int cx, int cz, int ly) {
      BlockPos pick = null;
      double bd = Double.MAX_VALUE;
      for (int dx = -STATION_RADIUS; dx <= STATION_RADIUS; dx++) {
         for (int dz = -STATION_RADIUS; dz <= STATION_RADIUS; dz++) {
            int x = cx + dx;
            int z = cz + dz;
            if (!this.cfg.contains(x, z)) {
               continue;
            }

            BlockPos cand = new BlockPos(x, ly, z);
            if (!this.canStand(mc.world, cand)) {
               continue;
            }

            double d = dx * dx + dz * dz;
            if (d < bd) {
               bd = d;
               pick = cand;
            }
         }
      }

      if (pick != null) {
         route.add(pick);
      }
   }

   /** [lo, hi] aralığını 5'lik bloklarla kaplayan merkez noktaları. */
   private static List<Integer> centers(int lo, int hi) {
      List<Integer> c = new ArrayList<>();
      int len = hi - lo + 1;
      if (len <= 2 * STATION_RADIUS + 1) {
         c.add((lo + hi) / 2);
         return c;
      }

      int step = 2 * STATION_RADIUS + 1;
      for (int x = lo + STATION_RADIUS; ; x += step) {
         if (x >= hi - STATION_RADIUS) {
            c.add(hi - STATION_RADIUS);
            break;
         }

         c.add(x);
      }

      return c;
   }

   // ------------------------------------------------------------------ ALANSIZ (eski) MOD

   private void step(MinecraftClient mc, Hand plantHand, Block plantBlock) {
      ClientPlayerEntity p = mc.player;
      Vec3d aim = aimPoint(this.targetPos);
      this.lookAt(p, aim);
      boolean settled = this.settled(p);
      double eyeDist = p.getEyePos().distanceTo(aim);
      double hd = Math.hypot(p.getX() - aim.x, p.getZ() - aim.z);
      boolean inReach = eyeDist <= REACH;
      boolean standOk = this.targetKind == AutoFarmClient.Kind.HARVEST ? inReach : hd <= this.approach;
      BlockHitResult bhr = null;
      boolean ok = false;
      if (inReach && p.raycast(p.getBlockInteractionRange(), 1.0F, false) instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK) {
         bhr = b;
         if (this.targetKind == AutoFarmClient.Kind.HARVEST) {
            ok = b.getBlockPos().equals(this.targetPos);
         } else {
            ok = b.getBlockPos().equals(this.targetPos.down()) && b.getSide() == Direction.UP;
         }

         if (!ok) {
            BlockPos hp = b.getBlockPos();
            BlockState hs = mc.world.getBlockState(hp);
            boolean harvestMode = this.cfg.mode == 0 || this.cfg.mode == 2;
            if (this.sameLevel(mc, hp) && harvestMode && isMature(hs) && this.cfg.isSelected(hs.getBlock()) && !this.blacklist.containsKey(hp)) {
               this.targetPos = hp.toImmutable();
               this.targetKind = AutoFarmClient.Kind.HARVEST;
               ok = true;
            } else if (plantBlock != null && b.getSide() == Direction.UP) {
               BlockPos above = hp.up();
               if (this.sameLevel(mc, above)
                  && mc.world.getBlockState(above).isAir()
                  && (this.cfg.mode == 1 || this.replant.containsKey(above))
                  && plantBlock.getDefaultState().canPlaceAt(mc.world, above)) {
                  this.targetPos = above.toImmutable();
                  this.targetKind = AutoFarmClient.Kind.PLANT;
                  ok = true;
               }
            }
         }
      }

      if (!ok && !standOk) {
         if (!this.navigate(mc, aim, this.targetKind == AutoFarmClient.Kind.HARVEST ? AutoFarmClient.Kind.HARVEST : AutoFarmClient.Kind.PLANT,
            this.targetKind == AutoFarmClient.Kind.HARVEST ? 3.0 : this.approach)) {
            this.releaseKeys(mc);
            this.giveUp();
         }
      } else {
         this.releaseKeys(mc);
         if (ok && settled && this.actionCooldown <= 0) {
            if (this.targetKind == AutoFarmClient.Kind.HARVEST) {
               mc.interactionManager.attackBlock(this.targetPos, bhr.getSide());
               p.swingHand(Hand.MAIN_HAND);
               if (this.cfg.mode == 2) {
                  this.replant.put(this.targetPos.toImmutable(), this.tickCounter + 100);
               }
            } else {
               ActionResult r = mc.interactionManager.interactBlock(p, plantHand, bhr);
               if (r.isAccepted()) {
                  p.swingHand(plantHand);
                  this.replant.remove(this.targetPos);
               } else {
                  this.blacklist.put(this.targetPos.toImmutable(), this.tickCounter + 200);
               }
            }

            this.actionCooldown = this.actionDelay();
            this.clearTarget();
         }
      }
   }

   private Hand findPlantHand(MinecraftClient mc, boolean allowMain) {
      ClientPlayerEntity p = mc.player;
      if (allowMain && this.isSelectedSeed(p.getMainHandStack())) {
         return Hand.MAIN_HAND;
      } else {
         return this.isSelectedSeed(p.getOffHandStack()) ? Hand.OFF_HAND : null;
      }
   }

   private void supply(MinecraftClient mc) {
      ClientPlayerEntity p = mc.player;
      ItemStack off = p.getOffHandStack();
      if (!off.isEmpty() && !isSeed(off)) {
         this.warn(mc, "2. el dolu! Ekin otomatik 2. ele alınır, eli boşalt.");
      } else if (this.actionCooldown <= 0) {
         for (int i = 0; i < 36; i++) {
            if (this.isSelectedSeed(p.getInventory().getStack(i))) {
               int slot = i < 9 ? i + 36 : i;
               mc.interactionManager.clickSlot(p.playerScreenHandler.syncId, slot, 40, SlotActionType.SWAP, p);
               this.actionCooldown = 10;
               return;
            }
         }

         this.warn(mc, "Çantada seçili ekin bulunamadı!");
      }
   }

   private void findTarget(MinecraftClient mc, Block plantBlock) {
      ClientPlayerEntity p = mc.player;
      boolean harvest = this.cfg.mode == 0 || this.cfg.mode == 2;
      Vec3d eye = p.getEyePos();
      BlockPos origin = p.getBlockPos();
      double best = Double.MAX_VALUE;
      BlockPos bestPos = null;
      AutoFarmClient.Kind bestKind = null;

      for (BlockPos pos : BlockPos.iterate(origin.add(-12, -1, -12), origin.add(12, 1, 12))) {
         if (!this.blacklist.containsKey(pos) && this.sameLevel(mc, pos)) {
            BlockState s = mc.world.getBlockState(pos);
            AutoFarmClient.Kind k = null;
            if (harvest && isMature(s) && this.cfg.isSelected(s.getBlock())) {
               k = AutoFarmClient.Kind.HARVEST;
            } else if (plantBlock != null
               && s.isAir()
               && (this.cfg.mode == 1 || this.replant.containsKey(pos))
               && plantBlock.getDefaultState().canPlaceAt(mc.world, pos)) {
               k = AutoFarmClient.Kind.PLANT;
            }

            if (k != null) {
               double d = eye.squaredDistanceTo(Vec3d.ofCenter(pos));
               if (d < best) {
                  best = d;
                  bestPos = pos.toImmutable();
                  bestKind = k;
               }
            }
         }
      }

      if (bestPos != null) {
         this.targetPos = bestPos;
         this.targetKind = bestKind;
         this.targetTicks = 0;
      }
   }

   private boolean targetStillValid(MinecraftClient mc, Block plantBlock) {
      return switch (this.targetKind) {
         case HARVEST -> {
            BlockState s = mc.world.getBlockState(this.targetPos);
            yield isMature(s) && this.cfg.isSelected(s.getBlock());
         }
         case PLANT -> plantBlock != null
            && mc.world.getBlockState(this.targetPos).isAir()
            && plantBlock.getDefaultState().canPlaceAt(mc.world, this.targetPos);
         case STATION -> false;
      };
   }

   private void clearTarget() {
      this.targetKind = null;
      this.targetPos = null;
      this.targetTicks = 0;
      this.path = null;
      this.pathIdx = 0;
      this.replans = 0;
      this.approach = 0.3;
   }

   private void giveUp() {
      if (this.targetPos != null) {
         this.blacklist.put(this.targetPos.toImmutable(), this.tickCounter + 600);
      }

      this.clearTarget();
   }

   // ------------------------------------------------------------------ bakış / hareket

   /** Hem kırma hem ekme için aynı nokta: çiftlik toprağının üst yüzü. Böylece kır -> ek arasında bakış değişmez. */
   private static Vec3d aimPoint(BlockPos cropPos) {
      return new Vec3d(cropPos.getX() + 0.5, cropPos.getY() - 1 + 0.9375, cropPos.getZ() + 0.5);
   }

   private float wantYaw(ClientPlayerEntity p, Vec3d target) {
      Vec3d eye = p.getEyePos();
      double dx = target.x - eye.x;
      double dz = target.z - eye.z;
      double h = Math.sqrt(dx * dx + dz * dz);
      return h < 0.5 ? p.getYaw() : (float)(Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
   }

   private float wantPitch(ClientPlayerEntity p, Vec3d target) {
      Vec3d eye = p.getEyePos();
      double dx = target.x - eye.x;
      double dy = target.y - eye.y;
      double dz = target.z - eye.z;
      double h = Math.sqrt(dx * dx + dz * dz);
      return (float)(-Math.toDegrees(Math.atan2(dy, h)));
   }

   private double lookError(ClientPlayerEntity p, Vec3d aim) {
      return Math.abs(MathHelper.wrapDegrees(this.wantYaw(p, aim) - p.getYaw())) + Math.abs(this.wantPitch(p, aim) - p.getPitch());
   }

   private void lookAt(ClientPlayerEntity p, Vec3d target) {
      float step = this.lookStep();
      float yawDiff = MathHelper.wrapDegrees(this.wantYaw(p, target) - p.getYaw());
      float pitchDiff = this.wantPitch(p, target) - p.getPitch();
      p.setYaw(p.getYaw() + MathHelper.clamp(yawDiff, -step, step));
      p.setPitch(MathHelper.clamp(p.getPitch() + MathHelper.clamp(pitchDiff, -step, step), -90.0F, 90.0F));
   }

   /**
    * Bu tick'te bakış açısı bir önceki tick'e göre değişmediyse true.
    * Dönüş sunucuya bir sonraki tick gider; eylemi dönüşle aynı tick'te yapmayız (hile gibi görünmesin).
    */
   private boolean settled(ClientPlayerEntity p) {
      boolean s = this.haveLast
         && Math.abs(MathHelper.wrapDegrees(p.getYaw() - this.lastYaw)) < 2.5F
         && Math.abs(p.getPitch() - this.lastPitch) < 2.5F;
      this.lastYaw = p.getYaw();
      this.lastPitch = p.getPitch();
      this.haveLast = true;
      return s;
   }

   private static BlockPos feetCell(ClientPlayerEntity p) {
      return BlockPos.ofFloored(p.getX(), p.getY(), p.getZ());
   }

   private boolean sameLevel(MinecraftClient mc, BlockPos pos) {
      int feetY = feetCell(mc.player).getY();
      return pos.getY() >= feetY - 1 && pos.getY() <= feetY + 1;
   }

   /** @return false: yol bulunamadı ya da çok kez yeniden planlandı (çağıran vazgeçmeli) */
   private boolean navigate(MinecraftClient mc, Vec3d goalAim, AutoFarmClient.Kind kind, double radius) {
      ClientPlayerEntity p = mc.player;
      if (this.path == null) {
         List<BlockPos> found = this.findPath(mc, goalAim, kind, radius);
         if (found == null && kind == AutoFarmClient.Kind.PLANT && radius < 0.9) {
            this.approach = 0.9;
            found = this.findPath(mc, goalAim, kind, 0.9);
         }

         if (found == null) {
            this.releaseKeys(mc);
            return false;
         }

         this.path = found;
         this.pathIdx = found.size() > 1 ? 1 : 0;
         if (++this.replans > 4) {
            this.releaseKeys(mc);
            return false;
         }
      }

      if (this.pathIdx >= this.path.size()) {
         this.path = null;
         this.releaseKeys(mc);
         return true;
      }

      BlockPos wp = this.path.get(this.pathIdx);
      double cx = wp.getX() + 0.5;
      double cz = wp.getZ() + 0.5;
      double d = Math.hypot(cx - p.getX(), cz - p.getZ());
      if (d < 0.5 && Math.abs(p.getY() - wp.getY()) < 1.6) {
         this.pathIdx++;
         if (this.pathIdx >= this.path.size()) {
            this.path = null;
            this.releaseKeys(mc);
            return true;
         }

         wp = this.path.get(this.pathIdx);
         cx = wp.getX() + 0.5;
         cz = wp.getZ() + 0.5;
      } else if (d > 3.5) {
         this.path = null;
         this.releaseKeys(mc);
         return true;
      }

      boolean needsClimb = wp.getY() > feetCell(p).getY();
      boolean jump = needsClimb && p.isOnGround() && p.horizontalCollision;
      this.moveToward(mc, cx, cz, jump);
      return true;
   }

   private void moveToward(MinecraftClient mc, double tx, double tz, boolean jump) {
      ClientPlayerEntity p = mc.player;
      double dx = tx - p.getX();
      double dz = tz - p.getZ();
      float moveYaw = (float)(Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
      float rel = MathHelper.wrapDegrees(moveYaw - p.getYaw());
      boolean f = Math.abs(rel) < 67.5F;
      boolean b = Math.abs(rel) > 112.5F;
      boolean r = rel > 22.5F && rel < 157.5F;
      boolean l = rel < -22.5F && rel > -157.5F;
      this.setKeys(mc, f, b, l, r, jump);
   }

   private void setKeys(MinecraftClient mc, boolean f, boolean b, boolean l, boolean r, boolean j) {
      mc.options.forwardKey.setPressed(f);
      mc.options.backKey.setPressed(b);
      mc.options.leftKey.setPressed(l);
      mc.options.rightKey.setPressed(r);
      mc.options.jumpKey.setPressed(j);
      mc.options.sprintKey.setPressed(f && !b);
      this.keysHeld = f || b || l || r || j;
   }

   private void releaseKeys(MinecraftClient mc) {
      if (this.keysHeld) {
         this.setKeys(mc, false, false, false, false, false);
      }
   }

   // ------------------------------------------------------------------ yol bulma (A*)

   private List<BlockPos> findPath(MinecraftClient mc, Vec3d aim, AutoFarmClient.Kind kind, double radius) {
      ClientWorld w = mc.world;
      BlockPos start = feetCell(mc.player);
      PriorityQueue<AutoFarmClient.Node> open = new PriorityQueue<>(Comparator.comparingDouble(nx -> nx.f));
      Map<BlockPos, AutoFarmClient.Node> best = new HashMap<>();
      Set<BlockPos> closed = new HashSet<>();
      AutoFarmClient.Node s = new AutoFarmClient.Node(start);
      s.g = 0.0;
      s.f = heuristic(start, aim);
      open.add(s);
      best.put(start, s);
      int expansions = 0;

      while (!open.isEmpty() && expansions < 8000) {
         AutoFarmClient.Node cur = open.poll();
         if (closed.add(cur.pos)) {
            expansions++;
            if (this.isGoal(cur.pos, aim, kind, radius)) {
               List<BlockPos> out = new ArrayList<>();

               for (AutoFarmClient.Node n = cur; n != null; n = n.parent) {
                  out.add(n.pos);
               }

               Collections.reverse(out);
               return out;
            }

            for (int[] d : DIRS) {
               for (int dy = 1; dy >= -3; dy--) {
                  BlockPos n = cur.pos.add(d[0], dy, d[1]);
                  if (!closed.contains(n)
                     && Math.abs(n.getX() - start.getX()) <= 48
                     && Math.abs(n.getZ() - start.getZ()) <= 48
                     && Math.abs(n.getY() - start.getY()) <= 24
                     && this.canMove(w, cur.pos, n, dy)) {
                     double cost = 1.0 + (dy > 0 ? 1.5 : 0.0) + (dy < 0 ? -dy * 1.0 : 0.0) + this.riskCost(w, n);
                     double g = cur.g + cost;
                     AutoFarmClient.Node ex = best.get(n);
                     if (ex == null || g < ex.g) {
                        AutoFarmClient.Node nn = new AutoFarmClient.Node(n);
                        nn.parent = cur;
                        nn.g = g;
                        nn.f = g + heuristic(n, aim);
                        best.put(n, nn);
                        open.add(nn);
                     }
                  }
               }
            }
         }
      }

      return null;
   }

   private static double heuristic(BlockPos pos, Vec3d aim) {
      return Math.hypot(pos.getX() + 0.5 - aim.x, pos.getZ() + 0.5 - aim.z);
   }

   private boolean isGoal(BlockPos pos, Vec3d aim, AutoFarmClient.Kind kind, double radius) {
      if (kind == AutoFarmClient.Kind.STATION) {
         double hd = Math.hypot(pos.getX() + 0.5 - aim.x, pos.getZ() + 0.5 - aim.z);
         return hd <= radius && Math.abs(pos.getY() + 0.5 - aim.y) <= 1.6;
      }

      if (kind == AutoFarmClient.Kind.PLANT) {
         double hd = Math.hypot(pos.getX() + 0.5 - aim.x, pos.getZ() + 0.5 - aim.z);
         return hd <= radius && this.targetPos != null && Math.abs(pos.getY() - this.targetPos.getY()) <= 1;
      }

      Vec3d eye = new Vec3d(pos.getX() + 0.5, pos.getY() + 1.62, pos.getZ() + 0.5);
      return eye.distanceTo(aim) <= radius;
   }

   private boolean canMove(ClientWorld w, BlockPos c, BlockPos n, int dy) {
      if (dy == 0) {
         return this.canStand(w, n);
      }

      if (dy > 0) {
         return this.canStand(w, n) && this.passable(w, c.up(2));
      }

      // Aşağı iniş (merdiven / basamak / en çok 3 blok düşüş): çiftlik toprağına düşme (ezilir)
      if (!this.canStand(w, n) || w.getBlockState(n.down()).isOf(Blocks.FARMLAND)) {
         return false;
      }

      for (int yy = n.getY(); yy <= c.getY() + 1; yy++) {
         if (!this.passable(w, new BlockPos(n.getX(), yy, n.getZ()))) {
            return false;
         }
      }

      return true;
   }

   private boolean canStand(ClientWorld w, BlockPos p) {
      if (this.passable(w, p) && this.passable(w, p.up())) {
         BlockPos fp = p.down();
         BlockState floor = w.getBlockState(fp);
         if (floor.getCollisionShape(w, fp).isEmpty()) {
            return false;
         } else {
            return !floor.getFluidState().isEmpty() ? false : !isHazard(floor.getBlock());
         }
      } else {
         return false;
      }
   }

   private boolean passable(ClientWorld w, BlockPos pos) {
      BlockState s = w.getBlockState(pos);
      if (!s.getCollisionShape(w, pos).isEmpty()) {
         return false;
      } else {
         return !s.getFluidState().isEmpty() ? false : !isHazard(s.getBlock());
      }
   }

   private static boolean isHazard(Block b) {
      return b == Blocks.FIRE
         || b == Blocks.SOUL_FIRE
         || b == Blocks.CACTUS
         || b == Blocks.SWEET_BERRY_BUSH
         || b == Blocks.COBWEB
         || b == Blocks.POWDER_SNOW
         || b == Blocks.MAGMA_BLOCK
         || b == Blocks.CAMPFIRE
         || b == Blocks.SOUL_CAMPFIRE
         || b == Blocks.WITHER_ROSE;
   }

   private double riskCost(ClientWorld w, BlockPos n) {
      double cost = 0.0;

      for (int[] d : DIRS) {
         BlockPos q = n.add(d[0], 0, d[1]);
         if (!w.getFluidState(q).isEmpty()) {
            cost += w.getFluidState(q).isIn(FluidTags.LAVA) ? 8.0 : 2.0;
         }

         if (!w.getFluidState(q.down()).isEmpty()) {
            cost += w.getFluidState(q.down()).isIn(FluidTags.LAVA) ? 8.0 : 2.0;
         }

         if (w.getBlockState(q.down()).getCollisionShape(w, q.down()).isEmpty()
            && w.getBlockState(q.down(2)).getCollisionShape(w, q.down(2)).isEmpty()) {
            cost++;
         }
      }

      return cost;
   }

   // ------------------------------------------------------------------ yardımcılar

   private static boolean isMature(BlockState state) {
      Block b = state.getBlock();
      if (b instanceof CropBlock crop) {
         return crop.isMature(state);
      } else {
         return b instanceof NetherWartBlock ? (Integer)state.get(NetherWartBlock.AGE) >= 3 : false;
      }
   }

   private static boolean isSeed(ItemStack stack) {
      if (!stack.isEmpty() && stack.getItem() instanceof BlockItem bi) {
         Block b = bi.getBlock();
         return b instanceof CropBlock || b instanceof NetherWartBlock;
      } else {
         return false;
      }
   }

   private boolean isSelectedSeed(ItemStack stack) {
      return isSeed(stack) && this.cfg.isSelected(((BlockItem)stack.getItem()).getBlock());
   }

   private static int fortuneLevel(ItemStack stack) {
      ItemEnchantmentsComponent ench = stack.getEnchantments();

      for (RegistryEntry<Enchantment> e : ench.getEnchantments()) {
         if (e.matchesKey(Enchantments.FORTUNE)) {
            return ench.getLevel(e);
         }
      }

      return 0;
   }

   private static enum Kind {
      HARVEST,
      PLANT,
      STATION;
   }

   private record Cell(BlockPos pos, AutoFarmClient.Kind kind) {
   }

   private static class Node {
      final BlockPos pos;
      AutoFarmClient.Node parent;
      double g;
      double f;

      Node(BlockPos pos) {
         this.pos = pos;
      }
   }
}
