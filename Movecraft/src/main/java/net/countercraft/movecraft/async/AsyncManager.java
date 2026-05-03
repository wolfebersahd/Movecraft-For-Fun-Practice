/*
 * This file is part of Movecraft.
 *
 *     Movecraft is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     Movecraft is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with Movecraft.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.countercraft.movecraft.async;

import com.google.common.collect.Lists;
import net.countercraft.movecraft.CruiseDirection;
import net.countercraft.movecraft.Movecraft;
import net.countercraft.movecraft.MovecraftLocation;
import net.countercraft.movecraft.async.rotation.RotationTask;
import net.countercraft.movecraft.async.translation.TranslationTask;
import net.countercraft.movecraft.craft.Craft;
import net.countercraft.movecraft.craft.CraftManager;
import net.countercraft.movecraft.craft.PilotedCraft;
import net.countercraft.movecraft.craft.PlayerCraft;
import net.countercraft.movecraft.craft.SinkingCraft;
import net.countercraft.movecraft.craft.type.CraftType;
import net.countercraft.movecraft.events.CraftReleaseEvent;
import net.countercraft.movecraft.mapUpdater.MapUpdateManager;
import net.countercraft.movecraft.util.hitboxes.HitBox;
import net.countercraft.movecraft.util.hitboxes.SolidHitBox;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

@Deprecated
public class AsyncManager extends BukkitRunnable {
    private final Map<AsyncTask, Craft> ownershipMap = new HashMap<>();
    private final BlockingQueue<AsyncTask> finishedAlgorithms = new LinkedBlockingQueue<>();
    private final Set<Craft> clearanceSet = new HashSet<>();
    private final Map<Craft, Integer> cooldownCache = new WeakHashMap<>();
    private static final Random RANDOM = new Random();

    public AsyncManager() {}

    public void submitTask(AsyncTask task, Craft c) {
        if (c.isNotProcessing()) {
            c.setProcessing(true);
            ownershipMap.put(task, c);
            task.runTask(Movecraft.getInstance());
        }
    }

    public void submitCompletedTask(AsyncTask task) {
        finishedAlgorithms.add(task);
    }

    private void processAlgorithmQueue() {
        int runLength = 10;
        int queueLength = finishedAlgorithms.size();

        runLength = Math.min(runLength, queueLength);

        for (int i = 0; i < runLength; i++) {
            boolean sentMapUpdate = false;
            AsyncTask poll = finishedAlgorithms.poll();
            Craft c = ownershipMap.get(poll);

            if (poll instanceof TranslationTask) {
                // Process translation task

                TranslationTask task = (TranslationTask) poll;
                sentMapUpdate = processTranslation(task, c);

            } else if (poll instanceof RotationTask) {
                // Process rotation task
                RotationTask task = (RotationTask) poll;
                sentMapUpdate = processRotation(task, c);

            }

            ownershipMap.remove(poll);

            // only mark the craft as having finished updating if you didn't
            // send any updates to the map updater. Otherwise the map updater
            // will mark the crafts once it is done with them.
            if (!sentMapUpdate) {
                clear(c);
            }
        }
    }

    /**
     * Processes translation task for its corresponding craft
     * @param task the task to process
     * @param c the craft this task belongs to
     * @return true if translation task succeded to process, otherwise false
     */
    private boolean processTranslation(@NotNull final TranslationTask task, @NotNull final Craft c) {

        // Check that the craft hasn't been sneakily unpiloted

        if (task.failed()) {
            // The craft translation failed
            if (!(c instanceof SinkingCraft))
                c.getAudience().sendMessage(Component.text(task.getFailMessage()));

            if (task.isCollisionExplosion()) {
                c.setHitBox(task.getNewHitBox());
                c.setFluidLocations(task.getNewFluidList());
                MapUpdateManager.getInstance().scheduleUpdates(task.getUpdates());
                CraftManager.getInstance().addReleaseTask(c);
                return true;
            }
            return false;
        }
        // The craft is clear to move, perform the block updates
        MapUpdateManager.getInstance().scheduleUpdates(task.getUpdates());

        c.setHitBox(task.getNewHitBox());
        c.setFluidLocations(task.getNewFluidList());
        return true;
    }

    /**
     * Processes rotation task for its corresponding craft
     * @param task the task to process
     * @param c the craft this task belongs to
     * @return true if translation task succeded to process, otherwise false
     */
    private boolean processRotation(@NotNull final RotationTask task, @NotNull final Craft c) {
        // Check that the craft hasn't been sneakily unpiloted
        if (!(c instanceof PilotedCraft) && !task.getIsSubCraft())
            return false;

        if (task.isFailed()) {
            // The craft translation failed, don't try to notify
            // them if there is no pilot
            c.getAudience().sendMessage(Component.text(task.getFailMessage()));
            return false;
        }


        MapUpdateManager.getInstance().scheduleUpdates(task.getUpdates());

        c.setHitBox(task.getNewHitBox());
        c.setFluidLocations(task.getNewFluidList());
        return true;
    }

    private void processCruise() {
        for (Craft craft : CraftManager.getInstance()) {
            if (craft == null || !craft.isNotProcessing() || !craft.getCruising())
                continue;

            long ticksElapsed = (System.currentTimeMillis() - craft.getLastCruiseUpdate()) / 50;
            World w = craft.getWorld();
            // if the craft should go slower underwater, make
            // time pass more slowly there
            if (craft.getType().getBoolProperty(CraftType.HALF_SPEED_UNDERWATER)
                    && craft.getHitBox().getMinY() < w.getSeaLevel())
                ticksElapsed >>= 1;
            // check direct controls to modify movement
            boolean bankLeft = false;
            boolean bankRight = false;
            boolean dive = false;
            if (craft instanceof PlayerCraft && ((PlayerCraft) craft).getPilotLocked()) {
                Player pilot = ((PlayerCraft) craft).getPilot();
                if (pilot.isSneaking())
                    dive = true;
                if (pilot.getInventory().getHeldItemSlot() == 3)
                    bankLeft = true;
                if (pilot.getInventory().getHeldItemSlot() == 5)
                    bankRight = true;
            }
            int tickCoolDown;
            if (cooldownCache.containsKey(craft)) {
                tickCoolDown = cooldownCache.get(craft);
            }
            else {
                tickCoolDown = craft.getTickCooldown();
                cooldownCache.put(craft,tickCoolDown);
            }

            // Account for banking and diving in speed calculations by changing the tickCoolDown
            int cruiseSkipBlocks = (int) craft.getType().getPerWorldProperty(
                    CraftType.PER_WORLD_CRUISE_SKIP_BLOCKS, w);
            if (craft.getCruiseDirection() != CruiseDirection.UP
                    && craft.getCruiseDirection() != CruiseDirection.DOWN) {
                if (bankLeft || bankRight) {
                    if (!dive) {
                        tickCoolDown *= (Math.sqrt(Math.pow(1 + cruiseSkipBlocks, 2)
                                + Math.pow(cruiseSkipBlocks >> 1, 2)) / (1 + cruiseSkipBlocks));
                    }
                    else {
                        tickCoolDown *= (Math.sqrt(Math.pow(1 + cruiseSkipBlocks, 2)
                                + Math.pow(cruiseSkipBlocks >> 1, 2) + 1) / (1 + cruiseSkipBlocks));
                    }
                }
                else if (dive) {
                    tickCoolDown *= (Math.sqrt(Math.pow(1 + cruiseSkipBlocks, 2) + 1) / (1 + cruiseSkipBlocks));
                }
            }

            if (Math.abs(ticksElapsed) < tickCoolDown)
                continue;

            cooldownCache.remove(craft);
            int dx = 0;
            int dz = 0;
            int dy = 0;

            int vertCruiseSkipBlocks = (int) craft.getType().getPerWorldProperty(CraftType.PER_WORLD_VERT_CRUISE_SKIP_BLOCKS, craft.getWorld());

            // ascend
            if (craft.getCruiseDirection() == CruiseDirection.UP)
                dy = 1 + vertCruiseSkipBlocks;
            // descend
            if (craft.getCruiseDirection() == CruiseDirection.DOWN) {
                dy = -1 - vertCruiseSkipBlocks;
                if (craft.getHitBox().getMinY() <= w.getSeaLevel())
                    dy = -1;
            }
            else if (dive) {
                dy = -((cruiseSkipBlocks + 1) >> 1);
                if (craft.getHitBox().getMinY() <= w.getSeaLevel())
                    dy = -1;
            }
            // ship faces west
            if (craft.getCruiseDirection() == CruiseDirection.WEST) {
                dx = -1 - cruiseSkipBlocks;
                if (bankRight)
                    dz = (-1 - cruiseSkipBlocks) >> 1;
                if (bankLeft)
                    dz = (1 + cruiseSkipBlocks) >> 1;
            }
            // ship faces east
            if (craft.getCruiseDirection() == CruiseDirection.EAST) {
                dx = 1 + cruiseSkipBlocks;
                if (bankLeft)
                    dz = (-1 - cruiseSkipBlocks) >> 1;
                if (bankRight)
                    dz = (1 + cruiseSkipBlocks) >> 1;
            }
            // ship faces north
            if (craft.getCruiseDirection() == CruiseDirection.SOUTH) {
                dz = 1 + cruiseSkipBlocks;
                if (bankRight)
                    dx = (-1 - cruiseSkipBlocks) >> 1;
                if (bankLeft)
                    dx = (1 + cruiseSkipBlocks) >> 1;
            }
            // ship faces south
            if (craft.getCruiseDirection() == CruiseDirection.NORTH) {
                dz = -1 - cruiseSkipBlocks;
                if (bankLeft)
                    dx = (-1 - cruiseSkipBlocks) >> 1;
                if (bankRight)
                    dx = (1 + cruiseSkipBlocks) >> 1;
            }
            if (craft.getType().getBoolProperty(CraftType.CRUISE_ON_PILOT)) {
                dy = craft.getType().getIntProperty(CraftType.CRUISE_ON_PILOT_VERT_MOVE);
            }
            if (craft.getType().getBoolProperty(CraftType.GEAR_SHIFTS_AFFECT_CRUISE_SKIP_BLOCKS)) {
                final int gearshift = craft.getCurrentGear();
                dx *= gearshift;
                dy *= gearshift;
                dz *= gearshift;
            }
            craft.translate(dx, dy, dz);
            craft.setLastTranslation(new MovecraftLocation(dx, dy, dz));
            craft.setLastCruiseUpdate(System.currentTimeMillis());
        }
    }

    //Controls sinking crafts
    private void processSinking() {
        // Copy the crafts before iteration to prevent concurrent modifications
        List<Craft> crafts = Lists.newArrayList(CraftManager.getInstance());
        for (Craft craft : crafts) {
            if (!(craft instanceof SinkingCraft))
                continue;

            if (craft.getHitBox().isEmpty()) {
                CraftManager.getInstance().release(craft, CraftReleaseEvent.Reason.SUNK, false);
                continue;
            }

            long ticksElapsed = (System.currentTimeMillis() - craft.getLastCruiseUpdate()) / 50L;
            if (Math.abs(ticksElapsed) < craft.getType().getIntProperty(CraftType.SINK_RATE_TICKS))
                continue;

            // Visual-only explosions while sinking. Randomized and size-scaled so small craft do not spam effects.
            spawnRandomExplosionVisuals(craft);

            if (craft.getHitBox().getMinY() == craft.getWorld().getMinHeight()) {
                removeBottomLayer(craft);

                if (craft.getHitBox().isEmpty()) {
                    CraftManager.getInstance().release(craft, CraftReleaseEvent.Reason.SUNK, false);
                    continue;
                }

                // Keep the same working behavior as the original simple sinking method:
                // after deleting the bottom layer, shift the hitbox bottom upward by one layer.
                MovecraftLocation start = new MovecraftLocation(
                        craft.getHitBox().getMinX(),
                        craft.getHitBox().getMinY() + 1,
                        craft.getHitBox().getMinZ()
                );
                MovecraftLocation end = new MovecraftLocation(
                        craft.getHitBox().getMaxX(),
                        craft.getHitBox().getMaxY(),
                        craft.getHitBox().getMaxZ()
                );
                SolidHitBox newHitBox = new SolidHitBox(start, end);
                craft.setHitBox((HitBox)newHitBox);

                craft.setLastCruiseUpdate(System.currentTimeMillis());
                continue;
            }

            int dx = 0;
            int dz = 0;
            if (craft.getType().getBoolProperty(CraftType.KEEP_MOVING_ON_SINK)) {
                dx = craft.getLastTranslation().getX();
                dz = craft.getLastTranslation().getZ();
            }
            craft.translate(dx, -1, dz);
            craft.setLastCruiseUpdate(System.currentTimeMillis());
        }
    }

    private void removeBottomLayer(Craft craft) {
        if (craft.getHitBox().isEmpty())
            return;

        int bottomY = craft.getHitBox().getMinY();
        World world = craft.getWorld();

        Set<MovecraftLocation> oldHitBox = craft.getHitBox().asSet();
        List<MovecraftLocation> bottomLayer = new ArrayList<>();

        for (MovecraftLocation movecraftLocation : oldHitBox) {
            if (movecraftLocation.getY() == bottomY)
                bottomLayer.add(movecraftLocation);
        }

        if (bottomLayer.isEmpty())
            return;

        Collections.shuffle(bottomLayer, RANDOM);
        spawnBottomLayerFallingBlocks(world, bottomLayer);

        for (MovecraftLocation movecraftLocation : bottomLayer) {
            Block block = movecraftLocation.toBukkit(world).getBlock();
            if (block.getType() != Material.AIR)
                block.setType(Material.AIR);
        }
    }

    private void spawnBottomLayerFallingBlocks(World world, List<MovecraftLocation> bottomLayer) {
        if (bottomLayer.isEmpty())
            return;

        // Hard-coded, size-relative, capped amount.
        // Tiny bottom layers may show 1. Larger layers cap at 12.
        int fallingBlocks = Math.max(1, Math.min(12, bottomLayer.size() / 40));

        for (int i = 0; i < fallingBlocks && i < bottomLayer.size(); i++) {
            MovecraftLocation movecraftLocation = bottomLayer.get(i);
            Location location = movecraftLocation.toBukkit(world);
            Block block = location.getBlock();

            if (block.isEmpty())
                continue;

            FallingBlock fallingBlock = world.spawnFallingBlock(
                    location.clone().add(0.5D, 0.0D, 0.5D),
                    block.getBlockData()
            );
            fallingBlock.setDropItem(false);
            fallingBlock.setHurtEntities(false);
            fallingBlock.setVelocity(new Vector(
                    (RANDOM.nextDouble() - 0.5D) * 0.25D,
                    -0.15D,
                    (RANDOM.nextDouble() - 0.5D) * 0.25D
            ));
        }
    }

    private void spawnRandomExplosionVisuals(Craft craft) {
        if (craft.getHitBox().isEmpty())
            return;

        Set<MovecraftLocation> hitBox = craft.getHitBox().asSet();
        int craftSize = hitBox.size();

        if (craftSize <= 0)
            return;

        // Size-scaled chance per sinking tick.
        // Small craft are rare, large craft are still capped to avoid spam.
        double explosionChance = Math.min(0.30D, Math.max(0.03D, craftSize / 3000.0D));
        if (RANDOM.nextDouble() > explosionChance)
            return;

        // Size-scaled amount, capped low for performance.
        int explosions = Math.max(1, Math.min(3, craftSize / 750));

        List<MovecraftLocation> blocks = new ArrayList<>(hitBox);
        Collections.shuffle(blocks, RANDOM);

        World world = craft.getWorld();
        for (int i = 0; i < explosions && i < blocks.size(); i++) {
            Location location = blocks.get(i).toBukkit(world).add(0.5D, 0.5D, 0.5D);

            // Visual-only explosion: no block damage and no entity damage.
            world.spawnParticle(Particle.EXPLOSION, location, 1);
            world.spawnParticle(Particle.SMOKE, location, 12, 0.6D, 0.6D, 0.6D, 0.02D);
            world.playSound(location, Sound.ENTITY_GENERIC_EXPLODE, 0.8F, 1.2F);
        }
    }

    public void run() {
        clearAll();

        processCruise();
        processSinking();
        processAlgorithmQueue();

        // now cleanup craft that are bugged and have not moved in the past 60 seconds,
        //  but have no pilot or are still processing
        for (Craft craft : CraftManager.getInstance()) {
            if (!(craft instanceof PilotedCraft)) {
                if (craft.getLastCruiseUpdate() < System.currentTimeMillis() - 60000)
                    CraftManager.getInstance().release(craft, CraftReleaseEvent.Reason.INACTIVE, true);
            }
            if (!craft.isNotProcessing()) {
                if (craft.getCruising()) {
                    if (craft.getLastCruiseUpdate() < System.currentTimeMillis() - 5000)
                        craft.setProcessing(false);
                }
            }
        }
    }

    private void clear(Craft c) {
        clearanceSet.add(c);
    }

    private void clearAll() {
        for (Craft c : clearanceSet) {
            c.setProcessing(false);
        }

        clearanceSet.clear();
    }
}
