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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * A funnel-shaped whirlpool modelled on the reference drawing: wide rim on top,
 * a cone tapering to a point, wavy streams inside and swirl rings around it.
 * Almost everything is drawn with dripping / falling water; a little indigo dust
 * keeps the outline readable.
 */
final class Whirlpool extends BukkitRunnable {

    private static final Particle.DustOptions EDGE = new Particle.DustOptions(Color.fromRGB(63, 75, 208), 1.3f);

    private final WhirlPlugin plugin;
    private final World world;
    private final Location center;
    private final Player caster;
    /** per captured player: {orbit angle, orbit radius} */
    private final Map<UUID, double[]> captured = new HashMap<>();
    private final Random rng = new Random();

    private final double height, topRadius, pullRadius, launchDistance, damage;
    private final int pullTicks, spinTicks, liftTicks;
    private final boolean excludeCaster;

    private int t = 0;
    private double angle = 0;

    Whirlpool(WhirlPlugin plugin, Location center, Player caster) {
        this.plugin = plugin;
        this.center = center;
        this.world = center.getWorld();
        this.caster = caster;
        FileConfiguration c = plugin.getConfig();
        height = c.getDouble("whirlpool.height", 7.0);
        topRadius = c.getDouble("whirlpool.top-radius", 4.5);
        pullRadius = c.getDouble("whirlpool.pull-radius", 10.0);
        launchDistance = c.getDouble("whirlpool.launch-distance", 10.0);
        damage = c.getDouble("whirlpool.drown-damage", 2.0);
        pullTicks = (int) (c.getDouble("whirlpool.pull-seconds", 5.5) * 20);
        spinTicks = (int) (c.getDouble("whirlpool.spin-seconds", 3.5) * 20);
        liftTicks = Math.max(1, Math.min(spinTicks, (int) (c.getDouble("whirlpool.lift-seconds", 1.5) * 20)));
        excludeCaster = c.getBoolean("whirlpool.exclude-caster", true);
    }

    // ------------------------------------------------------------------ main loop

    @Override
    public void run() {
        t++;
        if (t > pullTicks + spinTicks) {
            launch();
            cancel();
            return;
        }

        boolean spinning = t > pullTicks;
        double spin = spinning ? (t - pullTicks) / (double) spinTicks : 0;
        // radians per tick: gentle at first, then faster and faster
        double angVel = spinning ? 0.26 + 0.95 * spin * spin : 0.16 + 0.10 * ((double) t / pullTicks);
        angle += angVel;

        if (t % 2 == 0) render();
        ambience(spin);
        affectPlayers(spin, angVel);
    }

    // ------------------------------------------------------------------ visuals

    private double radiusAt(double f) {
        return 0.2 + (topRadius - 0.2) * f;
    }

    private void dust(double x, double y, double z) {
        world.spawnParticle(Particle.DUST, x, y, z, 1, 0, 0, 0, 0, EDGE, true);
    }

    /** The main brush: hanging drops that fall away, with some falling streaks mixed in. */
    private void water(double x, double y, double z) {
        Particle p = rng.nextInt(4) == 0 ? Particle.FALLING_WATER : Particle.DRIPPING_WATER;
        world.spawnParticle(p, x, y, z, 1, 0, 0, 0, 0, null, true);
    }

    private void render() {
        double cx = center.getX(), cy = center.getY(), cz = center.getZ();

        // 1) funnel outline: two spiralling edges from the tip up to the rim
        for (int i = 0; i <= 12; i++) {
            double f = i / 12.0;
            double r = radiusAt(f);
            for (int k = 0; k < 2; k++) {
                double a = angle + k * Math.PI + f * 1.2;
                double x = cx + Math.cos(a) * r, y = cy + f * height, z = cz + Math.sin(a) * r;
                if (i % 4 == 0) dust(x, y, z); else water(x, y, z);
            }
        }

        // 2) the wide rim on top, dripping all the way round
        int rimPoints = 24;
        for (int i = 0; i < rimPoints; i++) {
            double a = angle * 0.5 + i * (2 * Math.PI / rimPoints);
            double x = cx + Math.cos(a) * topRadius, z = cz + Math.sin(a) * topRadius;
            double y = cy + height;
            if (i % 4 == 0) dust(x, y, z); else water(x, y - 0.1, z);
        }

        // 3) loose swirl rings wrapping around the cone
        double[] ringHeights = {0.18, 0.38, 0.58, 0.8};
        for (double f : ringHeights) {
            double r = radiusAt(f) + 0.7 + 0.5 * Math.sin(t * 0.2 + f * 9);
            double start = angle * (0.6 + f);
            for (int i = 0; i < 14; i++) {
                double a = start + i * (1.6 * Math.PI / 14);
                double x = cx + Math.cos(a) * r, y = cy + f * height + Math.sin(i * 0.5) * 0.12, z = cz + Math.sin(a) * r;
                if (i % 5 == 0) dust(x, y, z); else water(x, y, z);
            }
        }

        // 4) wavy water streams inside the funnel
        for (int s = 0; s < 4; s++) {
            for (int j = 0; j <= 13; j++) {
                double f = 0.05 + j * (0.9 / 13.0);
                double a = angle * 0.8 + s * (Math.PI / 2) + f * 4.5;
                double r = radiusAt(f) * 0.55 + 0.18 * Math.sin(f * 12 + t * 0.35);
                double x = cx + Math.cos(a) * r, y = cy + f * height, z = cz + Math.sin(a) * r;
                if (j % 5 == 0) {
                    world.spawnParticle(Particle.SPLASH, x, y, z, 1, 0.05, 0.05, 0.05, 0, null, true);
                } else {
                    water(x, y, z);
                }
            }
        }

        // 5) extra water dripping off the funnel body
        for (int i = 0; i < 14; i++) {
            double f = 0.15 + rng.nextDouble() * 0.85;
            double a = rng.nextDouble() * Math.PI * 2;
            double r = radiusAt(f) + 0.1;
            water(cx + Math.cos(a) * r, cy + f * height, cz + Math.sin(a) * r);
        }

        // 6) spray at the tip
        world.spawnParticle(Particle.SPLASH, cx, cy + 0.1, cz, 5, 0.4, 0.1, 0.4, 0.05, null, true);
        water(cx, cy + 0.4, cz);
    }

    private void ambience(double spin) {
        if (t == 1 || t % 40 == 0) {
            world.playSound(center, Sound.BLOCK_CONDUIT_AMBIENT, 3f, 0.7f + (float) spin * 0.6f);
        }
        if (t % 12 == 0) {
            world.playSound(center, Sound.BLOCK_WATER_AMBIENT, 3f, 0.6f + (float) spin);
        }
        if (t == pullTicks) {
            world.playSound(center, Sound.ITEM_TRIDENT_RIPTIDE_1, 3f, 0.7f);
        }
        if (t == pullTicks + spinTicks - liftTicks) {
            world.playSound(center, Sound.ITEM_TRIDENT_RIPTIDE_2, 3f, 0.6f);
        }
    }

    // ------------------------------------------------------------------ players

    private void affectPlayers(double spin, double angVel) {
        // capture anybody who walks into range
        for (Entity en : world.getNearbyEntities(center, pullRadius, height + 4, pullRadius, x -> x instanceof Player)) {
            Player p = (Player) en;
            if (p.getGameMode() == GameMode.SPECTATOR || p.isDead()) continue;
            if (excludeCaster && p.equals(caster)) continue;
            if (captured.containsKey(p.getUniqueId())) continue;
            double dx = p.getLocation().getX() - center.getX();
            double dz = p.getLocation().getZ() - center.getZ();
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist <= pullRadius) {
                captured.put(p.getUniqueId(), new double[]{Math.atan2(dz, dx), dist});
            }
        }

        boolean spinning = t > pullTicks;
        for (UUID id : new ArrayList<>(captured.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isOnline() || p.isDead() || p.getWorld() != world) {
                captured.remove(id);
                continue;
            }
            plugin.guard().protect(p, 4000);   // never kick for "flying" while we toss them around

            double[] st = captured.get(id);
            Location loc = p.getLocation();

            // Each player rides a fixed orbit inside the funnel instead of being flung by
            // tangential velocity, so no matter how fast it spins they stay close to the vortex.
            // Orbit height: mid-funnel while spinning up, then in the last moments they're
            // dragged up the funnel wall to the rim so they get thrown from the very top.
            double baseF = 0.25 + 0.30 * spin;
            double lift = clamp((t - (pullTicks + spinTicks - liftTicks)) / (double) liftTicks, 0, 1);
            lift = lift * lift * (3 - 2 * lift);                   // ease in/out
            double f = baseF + (1.0 - baseF) * lift;

            double targetR = Math.max(1.1, radiusAt(f) * 0.8);     // always inside the cone
            double step = spinning ? 0.6 : 0.35;                   // how fast they're reeled in
            st[1] += clamp(targetR - st[1], -step, step);
            // cap their angular speed so the chord per tick stays reachable (no lagging / cutting corners)
            double maxAng = 2 * Math.asin(Math.min(1.0, 0.95 / Math.max(st[1], 0.5)));
            st[0] += Math.min(angVel, maxAng);

            double tx = center.getX() + Math.cos(st[0]) * st[1];
            double tz = center.getZ() + Math.sin(st[0]) * st[1];
            double ty = center.getY() + f * height + 0.2 * Math.sin(t * 0.3 + st[0]);

            double vx = (tx - loc.getX()) * 0.7;
            double vz = (tz - loc.getZ()) * 0.7;
            double hl = Math.sqrt(vx * vx + vz * vz);
            if (hl > 2.0) {
                vx = vx / hl * 2.0;
                vz = vz / hl * 2.0;
            }
            double vy = clamp((ty - loc.getY()) * 0.25, -0.4, 0.6);
            p.setVelocity(new Vector(vx, vy, vz));
            p.setFallDistance(0f);

            // drowning: air drains faster than vanilla refills it, then damage once per second
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

    // ------------------------------------------------------------------ launch

    private void launch() {
        world.playSound(center, Sound.ITEM_TRIDENT_RIPTIDE_3, 3f, 0.8f);
        world.playSound(center, Sound.ENTITY_GENERIC_SPLASH, 3f, 0.6f);
        world.spawnParticle(Particle.SPLASH, center.clone().add(0, 1.5, 0), 300, 2, 1.5, 2, 0.4);
        world.spawnParticle(Particle.FALLING_WATER, center.clone().add(0, 2.5, 0), 120, 2, 1.5, 2, 0.1);

        for (UUID id : captured.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isOnline() || p.isDead()) continue;

            double rx = p.getLocation().getX() - center.getX();
            double rz = p.getLocation().getZ() - center.getZ();
            double len = Math.sqrt(rx * rx + rz * rz);
            if (len < 0.3) {
                double a = rng.nextDouble() * Math.PI * 2;
                rx = Math.cos(a);
                rz = Math.sin(a);
                len = 1;
            }
            // they launch from the rim (~len blocks out and high up), so size the throw to land
            // about launchDistance from the center once they've dropped back down
            double travel = Math.max(3.0, launchDistance - len);
            double vy0 = 0.45;
            int airTicks = airTicks(p.getLocation().getY() - center.getY(), vy0);
            double horiz = travel * 0.09 / (1 - Math.pow(0.91, airTicks));   // horizontal drag ~0.91/tick

            p.setRemainingAir(p.getMaximumAir());
            p.setFallDistance(0f);
            plugin.guard().protect(p, 10000);
            p.setVelocity(new Vector(rx / len * horiz, vy0, rz / len * horiz));
        }
        captured.clear();
    }

    /** Ticks until something launched upward at vy0 from 'drop' blocks above the landing level comes back down. */
    private static int airTicks(double drop, double vy0) {
        double y = drop, vy = vy0;
        int n = 0;
        do {
            y += vy;
            vy = (vy - 0.08) * 0.98;
            n++;
        } while (y > 0 && n < 120);
        return Math.max(n, 8);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
