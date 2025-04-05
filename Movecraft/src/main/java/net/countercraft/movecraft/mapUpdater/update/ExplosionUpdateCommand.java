package net.countercraft.movecraft.mapUpdater.update;

import net.countercraft.movecraft.config.Settings;
import net.countercraft.movecraft.events.ExplosionEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;

import java.util.Objects;

public class ExplosionUpdateCommand extends UpdateCommand {
    private final Location explosionLocation;
    private final float explosionStrength;
    private final boolean incendiary;
    private final boolean shouldDamageBlocks;

    public ExplosionUpdateCommand(Location explosionLocation, float explosionStrength, boolean incendiary, boolean shouldDamageBlocks) {
        if (explosionStrength < 0) {
            throw new IllegalArgumentException("Explosion strength cannot be negative");
        }
        this.explosionLocation = explosionLocation;
        this.explosionStrength = explosionStrength;
        this.incendiary = incendiary;
        this.shouldDamageBlocks = shouldDamageBlocks;
    }

    public Location getLocation() {
        return explosionLocation;
    }

    public float getStrength() {
        return explosionStrength;
    }

    public boolean isIncendiary() {
        return incendiary;
    }

    public boolean shouldDamageBlocks() {
        return shouldDamageBlocks;
    }

    @Override
    public void doUpdate() {
        ExplosionEvent e = new ExplosionEvent(explosionLocation, explosionStrength, incendiary);  // Three args here
        Bukkit.getServer().getPluginManager().callEvent(e);
        if (e.isCancelled())
            return;

        if (Settings.Debug) {
            Bukkit.broadcastMessage("Explosion strength: " + explosionStrength + " at " + explosionLocation.toVector().toString());
        }

        this.createExplosion(explosionLocation.add(.5, .5, .5), explosionStrength, incendiary, shouldDamageBlocks);
    }

    private void createExplosion(Location loc, float explosionPower, boolean incendiary, boolean shouldDamageBlocks) {
        loc.getWorld().createExplosion(loc, explosionPower, shouldDamageBlocks, incendiary);

        // Cosmetic effects (if no block damage)
        if (!shouldDamageBlocks) {
            loc.getWorld().spawnParticle(org.bukkit.Particle.EXPLOSION_LARGE, loc, 1);
            loc.getWorld().playSound(loc, org.bukkit.Sound.ENTITY_TNT_PRIMED, 1.0F, 1.0F);
        }
    }

    @Override
    public int hashCode() {
        return Objects.hash(explosionLocation, explosionStrength, shouldDamageBlocks);
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof ExplosionUpdateCommand)) {
            return false;
        }
        ExplosionUpdateCommand other = (ExplosionUpdateCommand) obj;
        return this.explosionLocation.equals(other.explosionLocation) &&
                this.explosionStrength == other.explosionStrength &&
                this.incendiary == other.incendiary &&
                this.shouldDamageBlocks == other.shouldDamageBlocks;
    }
}
