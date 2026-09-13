package com.breakinblocks.neomcp.actions;

import com.breakinblocks.neomcp.config.NeoMcpConfig;
import com.mojang.blaze3d.platform.InputConstants;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Client-only, bounded primitive action controller. All public methods require the client thread. */
public final class ClientActionController implements AutoCloseable {
    private final Minecraft minecraft;
    private final AtomicLong nextId = new AtomicLong();
    private final Map<Long, ActionStatus> statuses = new java.util.HashMap<>();
    private ActiveAction activeAction;
    private boolean closed;

    public ClientActionController(Minecraft minecraft) {
        this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
        requireClientThread();
        NeoForge.EVENT_BUS.addListener(this::onClientTick);
    }

    public ActionResult lookAt(double x, double y, double z, int ticks) {
        requireOpen();
        requireFinite(x, y, z);
        requireTicks(ticks);
        LocalPlayer player = requirePlayer();
        Vec3 target = new Vec3(x, y, z);
        if (target.equals(player.getEyePosition(1.0F))) {
            throw new IllegalArgumentException("Look target must differ from the local player's eye position");
        }
        cancelActive();
        long id = begin("Looking at target");
        activeAction = new LookAction(id, player, x, y, z, ticks, player.getYRot(), player.getXRot());
        return result(id);
    }

    public ActionResult jump() {
        requireOpen();
        cancelActive();
        LocalPlayer player = requirePlayer();
        long id = begin("Jump executed");
        player.jumpFromGround();
        finish(id, ActionStatus.State.SUCCEEDED, "Jump executed");
        return result(id);
    }

    public ActionResult interactBlock(BlockPos position, Direction face) {
        return interactBlock(position, face, InteractionHand.MAIN_HAND);
    }

    public ActionResult interactBlock(BlockPos position, Direction face, InteractionHand hand) {
        requireOpen();
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(face, "face");
        Vec3 hit = new Vec3(
                position.getX() + 0.5D + face.getStepX() * 0.5D,
                position.getY() + 0.5D + face.getStepY() * 0.5D,
                position.getZ() + 0.5D + face.getStepZ() * 0.5D);
        return interactBlock(new BlockHitResult(hit, face, position, false), hand);
    }

    public ActionResult interactBlock(BlockHitResult hitResult, InteractionHand hand) {
        requireOpen();
        Objects.requireNonNull(hitResult, "hitResult");
        Objects.requireNonNull(hand, "hand");
        cancelActive();
        LocalPlayer player = requirePlayer();
        requireLevel();
        long id = begin("Block interaction dispatched");
        InteractionResult interaction = requireGameMode().useItemOn(player, hand, hitResult);
        swingIfConsumed(player, hand, interaction);
        return finishImmediate(id, interaction, "Block interaction");
    }

    public ActionResult interactEntity(Entity entity) {
        return interactEntity(entity, InteractionHand.MAIN_HAND);
    }

    public ActionResult interactEntity(Entity entity, InteractionHand hand) {
        requireOpen();
        Objects.requireNonNull(entity, "entity");
        return interactEntity(new EntityHitResult(entity, entity.position()), hand);
    }

    public ActionResult interactEntity(EntityHitResult hitResult, InteractionHand hand) {
        requireOpen();
        Objects.requireNonNull(hitResult, "hitResult");
        Objects.requireNonNull(hand, "hand");
        cancelActive();
        LocalPlayer player = requirePlayer();
        Entity entity = hitResult.getEntity();
        if (minecraft.level == null || entity.level() != minecraft.level) {
            throw new IllegalStateException("Entity is not in the active client level");
        }
        if (entity.isRemoved()) {
            throw new IllegalStateException("Entity has been removed");
        }
        long id = begin("Entity interaction dispatched");
        InteractionResult interaction = requireGameMode().interactAt(player, entity, hitResult, hand);
        if (!interaction.consumesAction()) {
            interaction = requireGameMode().interact(player, entity, hand);
        }
        swingIfConsumed(player, hand, interaction);
        return finishImmediate(id, interaction, "Entity interaction");
    }

    public ActionResult interactAir() {
        return interactAir(InteractionHand.MAIN_HAND);
    }

    public ActionResult interactAir(InteractionHand hand) {
        requireOpen();
        Objects.requireNonNull(hand, "hand");
        cancelActive();
        LocalPlayer player = requirePlayer();
        long id = begin("Air interaction dispatched");
        InteractionResult interaction = requireGameMode().useItem(player, hand);
        return finishImmediate(id, interaction, "Air interaction");
    }

    public ActionResult interactCrosshair(InteractionHand hand) {
        requireOpen();
        Objects.requireNonNull(hand, "hand");
        HitResult hitResult = minecraft.hitResult;
        if (hitResult == null) {
            throw new IllegalStateException("Minecraft has no current crosshair hit result");
        }
        return switch (hitResult.getType()) {
            case BLOCK -> {
                if (!(hitResult instanceof BlockHitResult blockHit)) {
                    throw new IllegalStateException("Crosshair block hit has an invalid result type");
                }
                yield interactBlock(blockHit, hand);
            }
            case ENTITY -> {
                if (!(hitResult instanceof EntityHitResult entityHit)) {
                    throw new IllegalStateException("Crosshair entity hit has an invalid result type");
                }
                yield interactEntity(entityHit, hand);
            }
            case MISS -> interactAir(hand);
        };
    }

    public ActionResult move(Movement movement, int ticks) {
        requireOpen();
        Objects.requireNonNull(movement, "movement");
        requireTicks(ticks);
        requirePlayer();
        cancelActive();
        long id = begin("Movement started");
        activeAction = new MoveAction(id, movement, ticks, keyFor(movement));
        setKey(((MoveAction) activeAction).key(), true);
        return result(id);
    }

    public ActionStatus status(long id) {
        requireClientThread();
        ActionStatus status = statuses.get(id);
        if (status == null) {
            throw new IllegalArgumentException("Unknown action id: " + id);
        }
        return status;
    }

    public void cancel() {
        requireOpen();
        cancelActive();
    }

    public ActionResult cancel(long id) {
        requireOpen();
        ActionStatus status = status(id);
        if (status.state() != ActionStatus.State.STARTED) {
            throw new IllegalStateException("Action is not running: " + id);
        }
        if (activeAction == null || activeId(activeAction) != id) {
            throw new IllegalStateException("Action is no longer active: " + id);
        }
        cancelActive();
        return result(id);
    }

    @Override
    public void close() {
        requireClientThread();
        if (!closed) {
            cancelActive();
            closed = true;
        }
    }

    private void onClientTick(ClientTickEvent.Post event) {
        if (!closed) {
            requireClientThread();
            if (activeAction != null) {
                activeAction.tick();
            }
        }
    }

    private final class LookAction implements ActiveAction {
        private final long id;
        private final LocalPlayer player;
        private final double x;
        private final double y;
        private final double z;
        private final int totalTicks;
        private final float startYaw;
        private final float startPitch;
        private int elapsed;

        private LookAction(long id, LocalPlayer player, double x, double y, double z, int totalTicks,
                           float startYaw, float startPitch) {
            this.id = id;
            this.player = player;
            this.x = x;
            this.y = y;
            this.z = z;
            this.totalTicks = totalTicks;
            this.startYaw = startYaw;
            this.startPitch = startPitch;
        }

        @Override
        public void tick() {
            if (player != minecraft.player) {
                finish(id, ActionStatus.State.FAILED, "Local player changed during look action");
                return;
            }
            elapsed++;
            Vec3 eye = player.getEyePosition(1.0F);
            double dx = x - eye.x;
            double dy = y - eye.y;
            double dz = z - eye.z;
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            float targetYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
            float targetPitch = (float) (-Math.toDegrees(Math.atan2(dy, horizontal)));
            float progress = (float) elapsed / totalTicks;
            float yaw = startYaw + Mth.wrapDegrees(targetYaw - startYaw) * progress;
            float boundedTargetPitch = clamp(targetPitch, -90.0F, 90.0F);
            float pitch = startPitch + (boundedTargetPitch - startPitch) * progress;
            player.setYRot(yaw);
            player.setYHeadRot(yaw);
            player.setXRot(pitch);
            updateElapsed(id, elapsed);
            if (elapsed >= totalTicks) {
                finish(id, ActionStatus.State.SUCCEEDED, "Look completed");
            }
        }
    }

    private final class MoveAction implements ActiveAction {
        private final long id;
        private final Movement movement;
        private final int totalTicks;
        private final KeyMapping key;
        private int elapsed;

        private MoveAction(long id, Movement movement, int totalTicks, KeyMapping key) {
            this.id = id;
            this.movement = movement;
            this.totalTicks = totalTicks;
            this.key = key;
        }

        @Override
        public void tick() {
            if (minecraft.player == null || minecraft.screen != null) {
                finish(id, ActionStatus.State.FAILED,
                        "Movement stopped because the local player or world screen changed");
                return;
            }
            elapsed++;
            setKey(key, true);
            updateElapsed(id, elapsed);
            if (elapsed >= totalTicks) {
                finish(id, ActionStatus.State.SUCCEEDED, movement + " movement completed");
            }
        }

        private KeyMapping key() {
            return key;
        }
    }

    public enum Movement { FORWARD, BACKWARD, LEFT, RIGHT }

    private interface ActiveAction { void tick(); }

    private ActionResult finishImmediate(long id, InteractionResult interaction, String name) {
        ActionStatus.State state = interaction.consumesAction()
                ? ActionStatus.State.SUCCEEDED
                : ActionStatus.State.FAILED;
        String message = name + (state == ActionStatus.State.SUCCEEDED ? " succeeded" : " was not consumed");
        finish(id, state, message);
        return result(id);
    }

    private long begin(String message) {
        long id = nextId.incrementAndGet();
        statuses.put(id, new ActionStatus(id, ActionStatus.State.STARTED, 0, message));
        return id;
    }

    private void finish(long id, ActionStatus.State state, String message) {
        releaseActiveKey();
        ActionStatus prior = statuses.get(id);
        int elapsed = prior == null ? 0 : prior.elapsedTicks();
        statuses.put(id, new ActionStatus(id, state, elapsed, message));
        if (activeAction != null && activeId(activeAction) == id) {
            activeAction = null;
        }
    }

    private void updateElapsed(long id, int elapsedTicks) {
        ActionStatus prior = statuses.get(id);
        if (prior != null && prior.state() == ActionStatus.State.STARTED) {
            statuses.put(id, new ActionStatus(id, prior.state(), elapsedTicks, prior.message()));
        }
    }

    private ActionResult result(long id) {
        ActionStatus status = statuses.get(id);
        return new ActionResult(id, status.state(), status.message());
    }

    private void cancelActive() {
        if (activeAction != null) {
            long id = activeId(activeAction);
            finish(id, ActionStatus.State.CANCELLED, "Action cancelled");
        }
    }

    private void releaseActiveKey() {
        if (activeAction instanceof MoveAction moveAction) {
            setKey(moveAction.key(), isPhysicallyDown(moveAction.key()));
        }
    }

    private void swingIfConsumed(LocalPlayer player, InteractionHand hand, InteractionResult interaction) {
        if (interaction.consumesAction() && interaction.shouldSwing()) {
            player.swing(hand);
        }
    }

    private boolean isPhysicallyDown(KeyMapping key) {
        InputConstants.Key inputKey = key.getKey();
        return inputKey.getType() == InputConstants.Type.KEYSYM
                && inputKey.getValue() != InputConstants.UNKNOWN.getValue()
                && InputConstants.isKeyDown(minecraft.getWindow().getWindow(), inputKey.getValue());
    }

    private long activeId(ActiveAction action) {
        if (action instanceof LookAction lookAction) return lookAction.id;
        if (action instanceof MoveAction moveAction) return moveAction.id;
        throw new IllegalStateException("Unknown active action type");
    }

    private KeyMapping keyFor(Movement movement) {
        return switch (movement) {
            case FORWARD -> minecraft.options.keyUp;
            case BACKWARD -> minecraft.options.keyDown;
            case LEFT -> minecraft.options.keyLeft;
            case RIGHT -> minecraft.options.keyRight;
        };
    }

    private void setKey(KeyMapping key, boolean down) { key.setDown(down); }
    private LocalPlayer requirePlayer() {
        LocalPlayer player = minecraft.player;
        if (player == null) throw new IllegalStateException("Minecraft local player is unavailable");
        return player;
    }
    private void requireLevel() {
        if (minecraft.level == null) {
            throw new IllegalStateException("Minecraft client level is unavailable");
        }
    }
    private net.minecraft.client.multiplayer.MultiPlayerGameMode requireGameMode() {
        net.minecraft.client.multiplayer.MultiPlayerGameMode gameMode = minecraft.gameMode;
        if (gameMode == null) throw new IllegalStateException("Minecraft multiplayer game mode is unavailable");
        return gameMode;
    }
    private void requireOpen() { requireClientThread(); if (closed) throw new IllegalStateException("Action controller is closed"); }
    private void requireClientThread() { if (!minecraft.isSameThread()) throw new IllegalStateException("Action controller must be used on the Minecraft client thread"); }
    private static void requireTicks(int ticks) {
        if (ticks < 1 || ticks > NeoMcpConfig.maxActionTicks()) {
            throw new IllegalArgumentException(
                    "Ticks must be between 1 and " + NeoMcpConfig.maxActionTicks());
        }
    }
    private static void requireFinite(double... values) { for (double value : values) if (!Double.isFinite(value)) throw new IllegalArgumentException("Target coordinates must be finite"); }
    private static float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }
}
