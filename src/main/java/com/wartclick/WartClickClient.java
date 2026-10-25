package com.wartclick;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.block.Blocks;
import net.minecraft.block.NetherWartBlock;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.registry.tag.ItemTags;
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
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class WartClickClient implements ClientModInitializer {
    // ---------- Сбор нароста ----------
    private static final int FORWARD = 4;
    private static final int BACK = 0;
    private static final int SIDE = 2;
    private static final int VERT_UP = 5;
    private static final int VERT_DOWN = 1;
    /** Максимум кликов за один тик. */
    private static final int MAX_CLICKS_PER_TICK = 6;
    private static final int SAME_POS_COOLDOWN = 3;
    private static final boolean REQUIRE_LINE_OF_SIGHT = true;
    private static final boolean DEBUG = true;
    private static final double[] GRID = {0.5, 0.05, 0.95};
    /** Допуск видимости: чем больше, тем больше блоков за краем яруса берётся. */
    private static final double GRAZE = 1.6;

    // ---------- Автопилот вагонетки ----------
    /** Какой клавишей разгонять вагонетку: true = D (вправо), false = A (влево). */
    private static final boolean PUSH_RIGHT = true;
    /** Радиус, в котором мод садится в вагонетку (блоков). */
    private static final double MOUNT_RANGE = 2.8;
    /** Пауза между попытками сесть (тиков). */
    private static final int MOUNT_RETRY_TICKS = 10;
    /** Если скорость ниже этого (блоков за тик) дольше STALL_TICKS, начинаем разгонять. */
    private static final double STALL_SPEED = 0.05;
    private static final int STALL_TICKS = 10;
    /** Разгоняем клавишей, пока скорость не дойдёт до этого значения (0.4 = максимум обычной вагонетки). */
    private static final double TARGET_SPEED = 0.3;
    /** После нажатия Shift не садиться обратно столько тиков. */
    private static final int SNEAK_SUPPRESS_TICKS = 60;

    // ---------- Поход к вагонетке ----------
    /** Включить самостоятельный поход к далёкой вагонетке. */
    private static final boolean WALK_TO_CART = true;
    /** Как далеко искать вагонетку (блоков). */
    private static final double SEARCH_RANGE = 96.0;
    /** Максимальный поворот камеры за тик (градусов). */
    private static final float MAX_TURN = 30.0F;
    /** Наклон камеры при ходьбе (градусов, 0 = горизонт). */
    private static final float WALK_PITCH = 10.0F;
    /** Проверка «застряли ли» раз в столько тиков. */
    private static final int STUCK_CHECK_TICKS = 40;
    /** Минимальное приближение за этот период (блоков), иначе считаем, что застряли. */
    private static final double STUCK_MIN_PROGRESS = 1.0;
    /** Сколько проверок подряд без прогресса, чтобы сдаться. */
    private static final int MAX_STUCK = 3;
    /** Сколько тиков ждать после отказа (опасность или застряли). */
    private static final int GIVE_UP_TICKS = 200;

    private static KeyBinding toggleKey;
    private static KeyBinding autoRideKey;

    // сбор
    private boolean enabled = false;
    private int tickCounter = 0;
    private Direction forwardDir = null;
    private final Map<BlockPos, Integer> recent = new HashMap<>();

    private final Set<BlockPos> dbgSeen = new HashSet<>();
    private final Set<BlockPos> dbgFar = new HashSet<>();
    private final Set<BlockPos> dbgBlocked = new HashSet<>();
    private final Set<BlockPos> dbgClicked = new HashSet<>();
    private boolean dbgNoHoe = false;
    private int dbgAllWart = 0;

    // автопилот
    private boolean autoRide = false;
    private int autoTick = 0;
    private int lastMountTry = -1000;
    private int suppressUntil = 0;
    private Vec3d lastCartPos = null;
    private int stallTicks = 0;
    private boolean pushing = false;
    private boolean pressedByUs = false;

    // поход
    private boolean walkKeysDown = false;
    private Float savedYaw = null;
    private Float savedPitch = null;
    private double walkLastDist = -1;
    private int walkCheckTick = 0;
    private int stuckCount = 0;
    private int walkPauseUntil = 0;

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.wartclick.toggle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_B,
                KeyBinding.Category.MISC
        ));
        autoRideKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.wartclick.autoride",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_N,
                KeyBinding.Category.MISC
        ));

        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void onTick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;

        while (toggleKey.wasPressed()) {
            enabled = !enabled;
            recent.clear();
            forwardDir = null;
            if (player != null) {
                player.sendMessage(Text.literal("WartClick: " + (enabled ? "ВКЛ" : "ВЫКЛ")), true);
            }
        }
        while (autoRideKey.wasPressed()) {
            autoRide = !autoRide;
            if (!autoRide) {
                releasePush(client);
                releaseWalk(client);
            }
            if (player != null) {
                player.sendMessage(Text.literal("Автопилот вагонетки: " + (autoRide ? "ВКЛ" : "ВЫКЛ")), true);
            }
        }

        autoRideTick(client);

        if (!enabled) return;
        if (player == null || client.world == null || client.interactionManager == null) return;
        if (client.currentScreen != null) return;

        tickCounter++;
        recent.entrySet().removeIf(e -> tickCounter - e.getValue() > SAME_POS_COOLDOWN);

        if (DEBUG && tickCounter % 20 == 0) {
            String msg = dbgNoHoe
                    ? "WartClick: в руке НЕ мотыга"
                    : "WartClick | в зоне нароста: " + dbgAllWart
                    + " | зрелых: " + dbgSeen.size()
                    + " | далеко: " + dbgFar.size()
                    + " | закрыто: " + dbgBlocked.size()
                    + " | клик: " + dbgClicked.size();
            player.sendMessage(Text.literal(msg), true);
            dbgSeen.clear();
            dbgFar.clear();
            dbgBlocked.clear();
            dbgClicked.clear();
            dbgAllWart = 0;
            dbgNoHoe = false;
        }

        ItemStack held = player.getMainHandStack();
        if (!held.isIn(ItemTags.HOES)) {
            dbgNoHoe = true;
            return;
        }

        updateForward(player);

        Direction fwd = forwardDir;
        Direction right = fwd.rotateYClockwise();
        int fx = fwd.getOffsetX(), fz = fwd.getOffsetZ();
        int rx = right.getOffsetX(), rz = right.getOffsetZ();

        Vec3d eye = player.getEyePos();
        double reach = player.getBlockInteractionRange();
        double reachSq = reach * reach;
        BlockPos center = player.getBlockPos();
        int clicks = 0;

        scan:
        for (int f = FORWARD; f >= -BACK; f--) {
            for (int s = -SIDE; s <= SIDE; s++) {
                for (int dy = VERT_UP; dy >= -VERT_DOWN; dy--) {
                    if (clicks >= MAX_CLICKS_PER_TICK) break scan;

                    BlockPos pos = center.add(fx * f + rx * s, dy, fz * f + rz * s);
                    BlockState state = client.world.getBlockState(pos);
                    if (!state.isOf(Blocks.NETHER_WART)) continue;
                    dbgAllWart++;
                    if (state.get(NetherWartBlock.AGE) < 3) continue;
                    dbgSeen.add(pos);
                    if (distToBlockSq(eye, pos) > reachSq) {
                        dbgFar.add(pos);
                        continue;
                    }
                    if (recent.containsKey(pos)) continue;

                    Vec3d hitVec = REQUIRE_LINE_OF_SIGHT
                            ? findVisiblePoint(client, player, eye, pos, reachSq)
                            : Vec3d.ofCenter(pos);
                    if (hitVec == null) {
                        dbgBlocked.add(pos);
                        continue;
                    }

                    Vec3d toEye = eye.subtract(hitVec);
                    Direction face = Direction.getFacing(toEye.x, toEye.y, toEye.z);
                    BlockHitResult hit = new BlockHitResult(hitVec, face, pos, false);
                    ActionResult result = client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
                    if (result.isAccepted()) {
                        player.swingHand(Hand.MAIN_HAND);
                    }
                    recent.put(pos, tickCounter);
                    dbgClicked.add(pos);
                    clicks++;
                }
            }
        }
    }

    // ================= Автопилот вагонетки =================

    private void autoRideTick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (!autoRide || player == null || client.world == null
                || client.interactionManager == null || client.currentScreen != null) {
            releasePush(client);
            releaseWalk(client);
            return;
        }

        autoTick++;
        if (player.isSneaking()) {
            suppressUntil = autoTick + SNEAK_SUPPRESS_TICKS;
        }

        Entity vehicle = player.getVehicle();
        if (vehicle instanceof AbstractMinecartEntity cart) {
            // Сели: останавливаем ходьбу и возвращаем камеру, какой она была.
            releaseWalk(client);
            if (savedYaw != null) {
                player.setYaw(savedYaw);
                player.setPitch(savedPitch);
                savedYaw = null;
                savedPitch = null;
            }
            walkLastDist = -1;
            stuckCount = 0;

            Vec3d pos = cart.getPos();
            double speed = 0;
            if (lastCartPos != null) {
                double dx = pos.x - lastCartPos.x;
                double dz = pos.z - lastCartPos.z;
                speed = Math.sqrt(dx * dx + dz * dz);
            }
            lastCartPos = pos;

            if (speed < STALL_SPEED) stallTicks++; else stallTicks = 0;
            if (!pushing && stallTicks >= STALL_TICKS) pushing = true;
            if (pushing && speed >= TARGET_SPEED) pushing = false;

            if (pushing) pushOn(client); else releasePush(client);
            return;
        }

        // Не в вагонетке: сбрасываем состояние.
        lastCartPos = null;
        stallTicks = 0;
        pushing = false;
        releasePush(client);

        if (autoTick < suppressUntil) {
            releaseWalk(client);
            return;
        }

        // Ищем ближайшую вагонетку.
        AbstractMinecartEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (AbstractMinecartEntity c : client.world.getEntitiesByClass(
                AbstractMinecartEntity.class,
                player.getBoundingBox().expand(SEARCH_RANGE),
                e -> true)) {
            double d = player.squaredDistanceTo(c);
            if (d < bestD) {
                bestD = d;
                best = c;
            }
        }
        if (best == null) {
            releaseWalk(client);
            return;
        }

        if (bestD <= MOUNT_RANGE * MOUNT_RANGE) {
            releaseWalk(client);
            if (autoTick - lastMountTry >= MOUNT_RETRY_TICKS) {
                lastMountTry = autoTick;
                client.interactionManager.interactEntity(player, best, Hand.MAIN_HAND);
            }
        } else if (WALK_TO_CART) {
            walkToward(client, player, best);
        }
    }

    private void walkToward(MinecraftClient client, ClientPlayerEntity player, AbstractMinecartEntity cart) {
        if (autoTick < walkPauseUntil) {
            releaseWalk(client);
            return;
        }

        if (savedYaw == null) {
            savedYaw = player.getYaw();
            savedPitch = player.getPitch();
            walkLastDist = -1;
            walkCheckTick = autoTick;
            stuckCount = 0;
        }

        double dx = cart.getX() - player.getX();
        double dz = cart.getZ() - player.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);

        // Плавный поворот к вагонетке.
        float targetYaw = (float) (MathHelper.atan2(dz, dx) * 57.29577951308232) - 90.0F;
        float diff = MathHelper.wrapDegrees(targetYaw - player.getYaw());
        float step = MathHelper.clamp(diff, -MAX_TURN, MAX_TURN);
        player.setYaw(player.getYaw() + step);
        player.setPitch(WALK_PITCH);

        // Обрыв или лава впереди: стоп.
        if (isDangerAhead(client, player)) {
            giveUp(client, player, "Автопилот: впереди обрыв или лава, остановился");
            return;
        }

        // Застряли: не приближаемся.
        if (autoTick - walkCheckTick >= STUCK_CHECK_TICKS) {
            if (walkLastDist >= 0 && walkLastDist - dist < STUCK_MIN_PROGRESS) {
                stuckCount++;
            } else {
                stuckCount = 0;
            }
            walkLastDist = dist;
            walkCheckTick = autoTick;
            if (stuckCount >= MAX_STUCK) {
                giveUp(client, player, "Автопилот: не могу дойти до вагонетки");
                return;
            }
        } else if (walkLastDist < 0) {
            walkLastDist = dist;
        }

        client.options.forwardKey.setPressed(true);
        client.options.sprintKey.setPressed(true);
        client.options.jumpKey.setPressed(player.horizontalCollision);
        walkKeysDown = true;
    }

    private void giveUp(MinecraftClient client, ClientPlayerEntity player, String message) {
        releaseWalk(client);
        walkPauseUntil = autoTick + GIVE_UP_TICKS;
        walkLastDist = -1;
        stuckCount = 0;
        player.sendMessage(Text.literal(message), true);
    }

    /** Впереди нет пола в пределах 3 блоков или есть лава. */
    private boolean isDangerAhead(MinecraftClient client, ClientPlayerEntity player) {
        ClientWorld w = client.world;
        Vec3d look = Vec3d.fromPolar(0.0F, player.getYaw());
        BlockPos ahead = BlockPos.ofFloored(
                player.getX() + look.x * 1.5,
                player.getY(),
                player.getZ() + look.z * 1.5);

        boolean floor = false;
        for (int dy = 0; dy >= -3; dy--) {
            BlockPos p = ahead.add(0, dy, 0);
            if (w.getFluidState(p).isIn(FluidTags.LAVA)) return true;
            if (dy < 0 && !w.getBlockState(p).getCollisionShape(w, p).isEmpty()) {
                floor = true;
            }
        }
        return !floor;
    }

    private void releaseWalk(MinecraftClient client) {
        if (!walkKeysDown) return;
        client.options.forwardKey.setPressed(false);
        client.options.sprintKey.setPressed(false);
        client.options.jumpKey.setPressed(false);
        walkKeysDown = false;
    }

    private void pushOn(MinecraftClient client) {
        KeyBinding k = PUSH_RIGHT ? client.options.rightKey : client.options.leftKey;
        k.setPressed(true);
        pressedByUs = true;
    }

    private void releasePush(MinecraftClient client) {
        if (!pressedByUs) return;
        KeyBinding k = PUSH_RIGHT ? client.options.rightKey : client.options.leftKey;
        k.setPressed(false);
        pressedByUs = false;
    }

    // ================= Сбор нароста: вспомогательные методы =================

    private static double distToBlockSq(Vec3d eye, BlockPos pos) {
        double dx = Math.max(Math.max(pos.getX() - eye.x, 0), eye.x - (pos.getX() + 1));
        double dy = Math.max(Math.max(pos.getY() - eye.y, 0), eye.y - (pos.getY() + 1));
        double dz = Math.max(Math.max(pos.getZ() - eye.z, 0), eye.z - (pos.getZ() + 1));
        return dx * dx + dy * dy + dz * dz;
    }

    private Vec3d findVisiblePoint(MinecraftClient client, ClientPlayerEntity player,
                                   Vec3d eye, BlockPos pos, double reachSq) {
        for (double gx : GRID) {
            for (double gy : GRID) {
                for (double gz : GRID) {
                    Vec3d target = new Vec3d(pos.getX() + gx, pos.getY() + gy, pos.getZ() + gz);
                    if (eye.squaredDistanceTo(target) > reachSq) continue;
                    HitResult r = client.world.raycast(new RaycastContext(
                            eye, target,
                            RaycastContext.ShapeType.COLLIDER,
                            RaycastContext.FluidHandling.NONE,
                            player));
                    if (r.getType() == HitResult.Type.MISS
                            || r.getPos().squaredDistanceTo(target) < GRAZE * GRAZE) {
                        return target;
                    }
                }
            }
        }
        return null;
    }

    private void updateForward(ClientPlayerEntity player) {
        Entity source = player.hasVehicle() ? player.getVehicle() : player;
        Vec3d v = source.getVelocity();
        if (v.x * v.x + v.z * v.z > 1.0E-4) {
            forwardDir = Math.abs(v.x) > Math.abs(v.z)
                    ? (v.x > 0 ? Direction.EAST : Direction.WEST)
                    : (v.z > 0 ? Direction.SOUTH : Direction.NORTH);
        } else if (forwardDir == null) {
            forwardDir = player.getHorizontalFacing();
        }
    }
}
