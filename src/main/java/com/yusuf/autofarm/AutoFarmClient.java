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
import net.minecraft.util.Hand;
import net.minecraft.util.ActionResult;
import net.minecraft.entity.ItemEntity;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.block.Blocks;
import net.minecraft.block.Block;
import net.minecraft.block.CropBlock;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Box;
import net.minecraft.block.NetherWartBlock;
import net.minecraft.util.math.Vec3d;
import net.minecraft.text.Text;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.math.MathHelper;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.util.hit.HitResult;

public class AutoFarmClient implements ClientModInitializer {
   private static final int SEARCH_RADIUS = 12;
   private static final double WALK_STOP = 3.0;
   private static final double PLANT_STRICT = 0.3;
   private static final double PLANT_RELAXED = 0.9;
   private static final int AIM_TICKS = 1;
   private static final int TARGET_TIMEOUT = 400;
   private static final int MAX_EXPANSIONS = 3000;
   private static final int PATH_RANGE = 24;
   private static final String[] MODE_NAMES = new String[]{"1: Kır (olgun ekinleri kırar)", "2: Ek (boş tarlaları eker)", "3: Kır + Ek (kırar ve geri eker)", "4: Kemik tozu (baktığın blok: ek + toz + kır)"};
   private static final int[][] DIRS = new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
   private final AutoFarmConfig cfg = AutoFarmConfig.get();
   private boolean enabled = false;
   private boolean openGui = false;
   private boolean prevG = false;
   private boolean announce = true;
   private BlockPos targetPos;
   private AutoFarmClient.Kind targetKind;
   private ItemEntity targetItem;
   private int targetTicks;
   private int aimedTicks;
   private int actionCooldown;
   private int tickCounter;
   private int warnCooldown;
   private boolean keysHeld;
   private List<BlockPos> path;
   private int pathIdx;
   private int replans;
   private double approach = 0.3;
   private final Map<BlockPos, Integer> blacklist = new HashMap<>();
   private final Map<BlockPos, Integer> replant = new HashMap<>();
   private final Set<Integer> ignoredItems = new HashSet<>();

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
      String msg = "AutoFarm " + (this.enabled ? "AÇIK" : "KAPALI") + " | Mod " + MODE_NAMES[this.cfg.mode];
      this.say(mc, msg);
      if (mc.player != null) {
         mc.player.sendMessage(Text.literal("[AutoFarm] " + msg), false);
      }
   }

   private void resetState(MinecraftClient mc) {
      this.clearTarget();
      this.blacklist.clear();
      this.replant.clear();
      this.ignoredItems.clear();
      this.releaseKeys(mc);
   }

   private void say(MinecraftClient mc, String msg) {
      if (mc.player != null) {
         mc.player.sendMessage(Text.literal(msg), true);
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
      return this.cfg.speed >= 10 ? 0 : Math.max(1, 6 - this.cfg.speed / 2);
   }

   private void onTick(MinecraftClient mc) {
      this.tickCounter++;
      if (this.announce && mc.player != null && mc.world != null) {
         this.announce = false;
         mc.player.sendMessage(Text.literal("[AutoFarm] Yüklendi. G = aç/kapat, R + G = menü, ya da /autofarm gui"), false);
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
      } else {
         if (this.warnCooldown > 0) {
            this.warnCooldown--;
         }

         if (this.actionCooldown > 0) {
            this.actionCooldown--;
         }

         if (this.enabled && mc.player != null && mc.world != null && mc.interactionManager != null && mc.currentScreen == null && !mc.player.isSpectator()) {
            if (this.tickCounter % 40 == 0) {
               this.blacklist.values().removeIf(t -> t < this.tickCounter);
               this.replant.values().removeIf(t -> t < this.tickCounter);
            }

            if (this.tickCounter % 1200 == 0) {
               this.ignoredItems.clear();
            }

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
         } else {
            this.releaseKeys(mc);
         }
      }
   }

   private void step(MinecraftClient mc, Hand plantHand, Block plantBlock) {
      ClientPlayerEntity p = mc.player;
      if (this.targetKind == AutoFarmClient.Kind.ITEM) {
         Vec3d ip = this.targetItem.getPos();
         this.lookAt(p, ip.add(0.0, 0.1, 0.0));
         double hd = Math.hypot(ip.x - p.getX(), ip.z - p.getZ());
         if (hd <= 0.6) {
            this.releaseKeys(mc);
         } else {
            this.navigate(mc, ip, AutoFarmClient.Kind.ITEM, 0.6);
         }
      } else {
         Vec3d aim = aimPoint(this.targetPos, this.targetKind);
         this.lookAt(p, aim);
         double eyeDist = p.getEyePos().distanceTo(aim);
         double hd = Math.hypot(p.getX() - aim.x, p.getZ() - aim.z);
         boolean inReach = eyeDist <= 3.0;
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
            this.aimedTicks = 0;
            if (this.targetKind == AutoFarmClient.Kind.HARVEST) {
               this.navigate(mc, aim, AutoFarmClient.Kind.HARVEST, 3.0);
            } else {
               this.navigate(mc, aim, AutoFarmClient.Kind.PLANT, this.approach);
            }
         } else {
            this.releaseKeys(mc);
            if (!ok) {
               this.aimedTicks = 0;
            } else if (++this.aimedTicks >= 1 && this.actionCooldown <= 0) {
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
         this.aimedTicks = 0;
      } else {
         if (harvest) {
            Box box = p.getBoundingBox().expand(10.0, 3.0, 10.0);
            ItemEntity bestItem = null;
            double bd = Double.MAX_VALUE;

            for (ItemEntity ie : mc.world.getEntitiesByClass(ItemEntity.class, box, x -> !x.isRemoved() && !this.ignoredItems.contains(x.getId()))) {
               double d = ie.squaredDistanceTo(p);
               if (d < bd) {
                  bd = d;
                  bestItem = ie;
               }
            }

            if (bestItem != null) {
               this.targetItem = bestItem;
               this.targetKind = AutoFarmClient.Kind.ITEM;
               this.targetTicks = 0;
               this.aimedTicks = 0;
            }
         }
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
         case ITEM -> this.targetItem != null && !this.targetItem.isRemoved();
      };
   }

   private void clearTarget() {
      this.targetKind = null;
      this.targetPos = null;
      this.targetItem = null;
      this.targetTicks = 0;
      this.aimedTicks = 0;
      this.path = null;
      this.pathIdx = 0;
      this.replans = 0;
      this.approach = 0.3;
   }

   private void giveUp() {
      if (this.targetKind == AutoFarmClient.Kind.ITEM && this.targetItem != null) {
         this.ignoredItems.add(this.targetItem.getId());
      } else if (this.targetPos != null) {
         this.blacklist.put(this.targetPos.toImmutable(), this.tickCounter + 600);
      }

      this.clearTarget();
   }

   private static Vec3d aimPoint(BlockPos pos, AutoFarmClient.Kind kind) {
      return kind == AutoFarmClient.Kind.HARVEST
         ? new Vec3d(pos.getX() + 0.5, pos.getY() + 0.35, pos.getZ() + 0.5)
         : new Vec3d(pos.getX() + 0.5, pos.getY() - 1 + 0.9375, pos.getZ() + 0.5);
   }

   private void lookAt(ClientPlayerEntity p, Vec3d target) {
      float step = this.lookStep();
      Vec3d eye = p.getEyePos();
      double dx = target.x - eye.x;
      double dy = target.y - eye.y;
      double dz = target.z - eye.z;
      double h = Math.sqrt(dx * dx + dz * dz);
      float wantYaw = h < 0.5 ? p.getYaw() : (float)(Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
      float wantPitch = (float)(-Math.toDegrees(Math.atan2(dy, h)));
      float yawDiff = MathHelper.wrapDegrees(wantYaw - p.getYaw());
      float pitchDiff = wantPitch - p.getPitch();
      p.setYaw(p.getYaw() + MathHelper.clamp(yawDiff, -step, step));
      p.setPitch(MathHelper.clamp(p.getPitch() + MathHelper.clamp(pitchDiff, -step, step), -90.0F, 90.0F));
   }

   private static BlockPos feetCell(ClientPlayerEntity p) {
      return BlockPos.ofFloored(p.getX(), p.getY(), p.getZ());
   }

   private boolean sameLevel(MinecraftClient mc, BlockPos pos) {
      int feetY = feetCell(mc.player).getY();
      return pos.getY() >= feetY - 1 && pos.getY() <= feetY + 1;
   }

   private void navigate(MinecraftClient mc, Vec3d goalAim, AutoFarmClient.Kind kind, double radius) {
      ClientPlayerEntity p = mc.player;
      if (this.path == null) {
         List<BlockPos> found = this.findPath(mc, goalAim, kind, radius);
         if (found == null && kind == AutoFarmClient.Kind.PLANT && radius < 0.9) {
            this.approach = 0.9;
            found = this.findPath(mc, goalAim, kind, 0.9);
         }

         if (found == null) {
            this.releaseKeys(mc);
            this.giveUp();
            return;
         }

         this.path = found;
         this.pathIdx = found.size() > 1 ? 1 : 0;
         if (++this.replans > 4) {
            this.releaseKeys(mc);
            this.giveUp();
            return;
         }
      }

      if (this.pathIdx >= this.path.size()) {
         this.path = null;
         this.releaseKeys(mc);
      } else {
         BlockPos wp = this.path.get(this.pathIdx);
         double cx = wp.getX() + 0.5;
         double cz = wp.getZ() + 0.5;
         double d = Math.hypot(cx - p.getX(), cz - p.getZ());
         if (d < 0.5 && Math.abs(p.getY() - wp.getY()) < 1.6) {
            this.pathIdx++;
            if (this.pathIdx >= this.path.size()) {
               this.path = null;
               this.releaseKeys(mc);
               return;
            }

            wp = this.path.get(this.pathIdx);
            cx = wp.getX() + 0.5;
            cz = wp.getZ() + 0.5;
         } else if (d > 3.5) {
            this.path = null;
            this.releaseKeys(mc);
            return;
         }

         boolean needsClimb = wp.getY() > feetCell(p).getY();
         boolean jump = needsClimb && p.isOnGround() && p.horizontalCollision;
         this.moveToward(mc, cx, cz, jump);
      }
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
      this.keysHeld = f || b || l || r || j;
   }

   private void releaseKeys(MinecraftClient mc) {
      if (this.keysHeld) {
         this.setKeys(mc, false, false, false, false, false);
      }
   }

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

      while (!open.isEmpty() && expansions < 3000) {
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
               for (int dy = 1; dy >= 0; dy--) {
                  BlockPos n = cur.pos.add(d[0], dy, d[1]);
                  if (!closed.contains(n)
                     && Math.abs(n.getX() - start.getX()) <= 24
                     && Math.abs(n.getZ() - start.getZ()) <= 24
                     && Math.abs(n.getY() - start.getY()) <= 8
                     && this.canMove(w, cur.pos, n, dy)) {
                     double cost = 1.0 + (dy > 0 ? 1.5 : 0.0) + (dy < 0 ? 1.0 : 0.0) + this.riskCost(w, n);
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
      if (kind == AutoFarmClient.Kind.HARVEST) {
         Vec3d eye = new Vec3d(pos.getX() + 0.5, pos.getY() + 1.62, pos.getZ() + 0.5);
         return eye.distanceTo(aim) <= 3.0;
      } else {
         double hd = Math.hypot(pos.getX() + 0.5 - aim.x, pos.getZ() + 0.5 - aim.z);
         return kind == AutoFarmClient.Kind.PLANT
            ? hd <= radius && Math.abs(pos.getY() - this.targetPos.getY()) <= 1
            : hd <= radius && Math.abs(pos.getY() + 0.5 - aim.y) <= 1.6;
      }
   }

   private boolean canMove(ClientWorld w, BlockPos c, BlockPos n, int dy) {
      return dy == 0 ? this.canStand(w, n) : this.canStand(w, n) && this.passable(w, c.up(2));
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
      ITEM;
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
