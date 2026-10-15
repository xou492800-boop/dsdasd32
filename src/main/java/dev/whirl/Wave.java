package dev.whirl;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * A water-bending wave. It travels wherever the caster looks (left, right, up, down, any
 * angle), scoops up players, holds them (drowning + freezing, no ender pearls) and carries
 * them along. Width and depth stay constant; only the height shrinks over time.
 */
final class Wave extends BukkitRunnable {

    private static final Particle.DustOptions DEEP = new Particle.DustOptions(Color.fromRGB(63, 75, 208), 1.3f);
    private static final Particle.DustOptions LIGHT = new Particle.DustOptions(Color.fromRGB(125, 155, 255), 1.0f);
    private static final Particle.DustOptions FOAM = new Particle.DustOptions(Color.fromRGB(235, 248, 255), 1.4f);

    private final WhirlPlugin plugin;
    private final World world;
    private final Player caster;
    private final Random rng = new Random();

    private final double width, depth, startH, endH, speed, turn, damage;
    private final int totalTicks;
    private final boolean excludeCaster;
    private final Set<UUID> captured = new HashSet<>();

    private final Vector pos;   // center of the wave's base
    private Vector dir;         // travel direction (full 3D, follows the caster's look)
    private Vector right;       // across the wave
    private Vector up;          // from the base toward the crest

    private int t = 0;
    private double h;

    Wave(WhirlPlugin plugin, Player caster) {
        this.plugin = plugin;
        this.caster = caster;
        this.world = caster.getWorld();

        FileConfiguration c = plugin.getConfig();
        width = c.getDouble("wave.width", 9.0);
        depth = c.getDouble("wave.depth", 3.0);
        startH = c.getDouble("wave.start-height", 5.0);
        endH = c.getDouble("wave.end-height", 1.2);
        speed = c.getDouble("wave.speed", 0.35);
        turn = clamp(c.getDouble("wave.turn-rate", 0.2), 0.02, 1.0);
        damage = c.getDouble("wave.drown-damage", 2.0);
        excludeCaster = c.getBoolean("wave.exclude-caster", true);
        totalTicks = (int) (c.getDouble("wave.seconds", 4.5) * 20);
        h = startH;

        Vector look = caster.getEyeLocation().getDirection().normalize();
        Vector flat = new Vector(look.getX(), 0, look.getZ());
        if (flat.lengthSquared() < 1.0E-4) flat = new Vector(0, 0, 1);
        flat.normalize();

        Location feet = caster.getLocation();
        pos = new Vector(feet.getX() + flat.getX() * 2, feet.getY(), feet.getZ() + flat.getZ() * 2);
        dir = look.clone();
        right = new Vector(-flat.getZ(), 0, flat.getX());
        updateAxes();

        world.playSound(feet, Sound.ITEM_TRIDENT_RIPTIDE_2, 3f, 0.7f);
        world.playSound(feet, Sound.ENTITY_GENERIC_SPLASH, 3f, 0.6f);
    }

    // ------------------------------------------------------------------ main loop

    @Override
    public void run() {
        t++;
        if (t > totalTicks) {
            finish();
            cancel();
            return;
        }

        steer();
        advance();
        h = startH + (endH - startH) * ((double) t / totalTicks);   // only the height shrinks

        capture();
        affectPlayers();
        if (t % 2 == 0) render();
        if (t % 10 == 0) world.playSound(center(), Sound.BLOCK_WATER_AMBIENT, 3f, 0.7f);
    }

    /** Water-bending: ease the heading toward wherever the caster is looking right now. */
    private void steer() {
        if (!caster.isOnline() || caster.isDead()) return;
        Vector look = caster.getEyeLocation().getDirection().normalize();
        Vector blended = dir.clone().multiply(1 - turn).add(look.clone().multiply(turn));
        if (blended.lengthSquared() < 1.0E-6) blended = look;
        dir = blended.normalize();
        updateAxes();
    }

    /** Rebuild the wave's axes from its heading. */
    private void updateAxes() {
        Vector r = dir.clone().crossProduct(new Vector(0, 1, 0));
        if (r.lengthSquared() < 1.0E-4) r = right.clone();   // straight up/down: keep the old side axis
        right = r.normalize();
        up = right.clone().crossProduct(dir).normalize();
    }

    /** Move along the heading, but don't plough through solid blocks. */
    private void advance() {
        Vector next = pos.clone().add(dir.clone().multiply(speed));
        Vector probe = next.clone()
                .add(dir.clone().multiply(depth / 2))
                .add(up.clone().multiply(0.8));
        if (probe.getY() < world.getMinHeight() || probe.getY() > world.getMaxHeight()) return;
        if (!probe.toLocation(world).getBlock().isPassable()) return;
        pos.copy(next);
    }

    private Location center() {
        Vector c = pos.clone().add(up.clone().multiply(h * 0.5));
        return c.toLocation(world);
    }

    private Vector at(double a, double l, double y) {
        return pos.clone()
                .add(dir.clone().multiply(a))
                .add(right.clone().multiply(l))
                .add(up.clone().multiply(y));
    }

    // ------------------------------------------------------------------ visuals

    private void dust(Vector v, Particle.DustOptions o) {
        world.spawnParticle(Particle.DUST, v.getX(), v.getY(), v.getZ(), 1, 0, 0, 0, 0, o, true);
    }

    /** The main brush: hanging drops that fall away, with some falling streaks mixed in. */
    private void water(Vector v) {
        Particle p = rng.nextInt(4) == 0 ? Particle.FALLING_WATER : Particle.DRIPPING_WATER;
        world.spawnParticle(p, v.getX(), v.getY(), v.getZ(), 1, 0, 0, 0, 0, null, true);
    }

    /** Edge taper so the wall arcs like a real wave instead of being a flat slab. */
    private double edgeFactor(double l) {
        return 0.35 + 0.65 * Math.cos(l / (width / 2) * Math.PI / 2);
    }

    private void render() {
        int columns = (int) Math.max(4, width / 0.6);

        for (int i = 0; i <= columns; i++) {
            double l = -width / 2 + width * i / columns;
            double hl = h * edgeFactor(l);
            for (int k = 0; k <= 7; k++) {
                double f = k / 7.0;
                // face profile: slopes up from the back and curls forward at the crest
                double a = -depth / 2 + depth * f * f + 0.3 * depth * Math.pow(f, 8);
                Vector v = at(a, l, hl * f);
                if (k == 7) {
                    if (i % 2 == 0) dust(v, FOAM); else water(v);
                } else if (k % 3 == 0) {
                    dust(v, k % 2 == 0 ? DEEP : LIGHT);
                } else {
                    water(v);
                }
            }
            // spray along the foot of the wave
            if (i % 2 == 0) {
                Vector foot = at(depth / 2, l, 0.1);
                world.spawnParticle(Particle.SPLASH, foot.getX(), foot.getY(), foot.getZ(), 3, 0.3, 0.1, 0.3, 0.1, null, true);
            }
        }

        // water body: mostly drips, a few bubbles and dust
        for (int i = 0; i < 30; i++) {
            double l = (rng.nextDouble() - 0.5) * width * 0.95;
            double f = rng.nextDouble();
            double a = (rng.nextDouble() - 0.5) * depth;
            Vector v = at(a, l, h * edgeFactor(l) * f);
            if (i % 5 == 0) {
                world.spawnParticle(Particle.BUBBLE_POP, v.getX(), v.getY(), v.getZ(), 1, 0.1, 0.1, 0.1, 0, null, true);
            } else if (i % 5 == 1) {
                dust(v, LIGHT);
            } else {
                water(v);
            }
        }
    }

    // ------------------------------------------------------------------ players

    private void capture() {
        double reach = Math.max(width, depth) + startH + 2;
        for (Entity en : world.getNearbyEntities(center(), reach, reach, reach, x -> x instanceof Player)) {
            Player p = (Player) en;
            if (p.getGameMode() == GameMode.SPECTATOR || p.isDead()) continue;
            if (excludeCaster && p.equals(caster)) continue;
            if (captured.contains(p.getUniqueId())) continue;

            Vector rel = p.getLocation().toVector().subtract(pos);
            double a = rel.dot(dir), l = rel.dot(right), y = rel.dot(up);
            if (Math.abs(a) <= depth / 2 + 1.2 && Math.abs(l) <= width / 2 && y >= -1.5 && y <= h + 1.5) {
                captured.add(p.getUniqueId());
                world.playSound(p.getLocation(), Sound.ENTITY_PLAYER_SPLASH_HIGH_SPEED, 1f, 0.8f);
            }
        }
    }

    private void affectPlayers() {
        for (UUID id : new HashSet<>(captured)) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isOnline() || p.isDead() || p.getWorld() != world) {
                captured.remove(id);
                continue;
            }
            plugin.guard().protect(p, 4000);   // no flying kicks
            plugin.lockPearls(p, 1000);        // no ender pearls while held (and a moment after)

            Location loc = p.getLocation();
            Vector rel = loc.toVector().subtract(pos);
            double a = rel.dot(dir), l = rel.dot(right);

            // hold them inside the wave body; the wave carries them wherever it's steered
            double ta = clamp(a, -depth / 2 + 0.5, depth / 2 - 0.5);
            double tl = clamp(l, -(width / 2 - 0.8), width / 2 - 0.8);
            double ty = Math.max(1.0, h * 0.55) + 0.25 * Math.sin(t * 0.25);
            Vector target = at(ta, tl, ty);

            Vector v = target.subtract(loc.toVector()).multiply(0.4);
            v.add(dir.clone().multiply(speed));   // feed-forward so the wave never outruns them
            if (v.length() > 1.2) v.normalize().multiply(1.2);
            p.setVelocity(v);
            p.setFallDistance(0f);

            // freeze: full powder-snow overlay + frost particles
            p.setFreezeTicks(p.getMaxFreezeTicks());
            if (t % 2 == 0) {
                world.spawnParticle(Particle.SNOWFLAKE, loc.clone().add(0, 1, 0), 4, 0.35, 0.5, 0.35, 0.02);
            }

            // drown: air drains faster than vanilla refills it, then damage once per second
            int air = p.getRemainingAir() - 14;
            if (air <= 0) {
                air = 0;
                if (t % 20 == 0) {
                    p.damage(damage);
                    world.playSound(loc, Sound.ENTITY_PLAYER_HURT_DROWN, 1f, 1f);
                }
            }
            p.setRemainingAir(air);
            if (t % 3 == 0) {
                world.spawnParticle(Particle.BUBBLE_POP, p.getEyeLocation(), 4, 0.3, 0.3, 0.3, 0.02);
            }
        }
    }

    private void finish() {
        Location c = center();
        world.spawnParticle(Particle.SPLASH, c, 120, 2, 0.5, 2, 0.3);
        world.spawnParticle(Particle.FALLING_WATER, c, 80, 2, 0.5, 2, 0.1);
        world.playSound(c, Sound.ENTITY_GENERIC_SPLASH, 3f, 0.8f);
        for (UUID id : captured) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isOnline()) continue;
            p.setFreezeTicks(0);
            p.setRemainingAir(p.getMaximumAir());
            p.setFallDistance(0f);
            plugin.guard().protect(p, 6000);   // short grace so landing never triggers a kick
        }
        captured.clear();
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
