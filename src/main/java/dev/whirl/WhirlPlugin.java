package dev.whirl;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Entity;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Set;
import java.util.Map;
import java.util.UUID;

public final class WhirlPlugin extends JavaPlugin implements Listener {

    /** Hard-coded: only these UUIDs can use /whirl and hold / pick up the shell. */
    private static final Set<UUID> ALLOWED = Set.of(
            UUID.fromString("c74b6b9d-8ea6-4057-99ce-257f719ec458"), // xo1u
            UUID.fromString("01668aa0-0c1e-44ba-b8e6-c21a8d643ffe")  // DSBV
    );

    private NamespacedKey itemKey;
    private final FlightGuard guard = new FlightGuard();
    private final Map<UUID, Long> whirlCooldowns = new HashMap<>();
    private final Map<UUID, Long> waveCooldowns = new HashMap<>();
    private final Map<UUID, Long> pearlLocks = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        itemKey = new NamespacedKey(this, "whirl_shell");
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().runTaskTimer(this, guard::tick, 10L, 10L);
        getServer().getScheduler().runTaskTimer(this, this::sweep, 5L, 5L);
        if (getCommand("whirl") != null) {
            getCommand("whirl").setExecutor(this);
        }
    }

    @Override
    public void onDisable() {
        guard.restoreAll();
    }

    FlightGuard guard() {
        return guard;
    }

    // ------------------------------------------------------------------ access

    private static boolean isAllowed(UUID id) {
        return ALLOWED.contains(id);
    }

    private static boolean isAllowed(Player p) {
        return isAllowed(p.getUniqueId());
    }

    /** Strips the shell from anyone who isn't allowed to have it (any slot, offhand, armor, cursor). */
    private void sweep() {
        for (Player p : getServer().getOnlinePlayers()) {
            if (isAllowed(p)) continue;
            ItemStack[] contents = p.getInventory().getContents();
            for (int i = 0; i < contents.length; i++) {
                if (isShell(contents[i])) p.getInventory().setItem(i, null);
            }
            if (isShell(p.getItemOnCursor())) p.setItemOnCursor(null);
        }
    }

    // ------------------------------------------------------------------ item

    private ItemStack createShell() {
        ItemStack item = new ItemStack(Material.NAUTILUS_SHELL);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(" ");                    // no name
        meta.setHideTooltip(true);                   // no lore / tooltip at all
        meta.setEnchantmentGlintOverride(true);      // enchantment glint
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isShell(ItemStack item) {
        if (item == null || item.getType() != Material.NAUTILUS_SHELL || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(itemKey, PersistentDataType.BYTE);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only.");
            return true;
        }
        if (!isAllowed(p)) {
            p.sendMessage("Unknown command. Type \"/help\" for help.");
            return true;
        }
        Map<Integer, ItemStack> left = p.getInventory().addItem(createShell());
        left.values().forEach(i -> p.getWorld().dropItemNaturally(p.getLocation(), i));
        return true;
    }

    // ------------------------------------------------------------------ summon

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action act = e.getAction();
        boolean right = act == Action.RIGHT_CLICK_AIR || act == Action.RIGHT_CLICK_BLOCK;
        boolean left = act == Action.LEFT_CLICK_AIR || act == Action.LEFT_CLICK_BLOCK;
        if (!right && !left) return;
        Player p = e.getPlayer();
        if (!isShell(p.getInventory().getItemInMainHand())) return;

        e.setCancelled(true);
        if (!p.isSneaking()) return;

        if (right) summonWhirlpool(p);   // shift + right click
        else summonWave(p);              // shift + left click
    }

    /** Left-clicking a player fires a damage event instead of an interact event, so catch that too. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPunch(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) return;
        if (!isShell(p.getInventory().getItemInMainHand()) || !p.isSneaking()) return;
        e.setCancelled(true);
        summonWave(p);
    }

    private boolean onCooldown(Map<UUID, Long> map, Player p, String key) {
        long now = System.currentTimeMillis();
        long cdMs = (long) (getConfig().getDouble(key, 1.5) * 1000);
        Long last = map.get(p.getUniqueId());
        if (last != null && now - last < cdMs) return true;
        map.put(p.getUniqueId(), now);
        return false;
    }

    private void summonWhirlpool(Player p) {
        if (!isAllowed(p) || onCooldown(whirlCooldowns, p, "cooldown-seconds")) return;
        new Whirlpool(this, findSpot(p), p).runTaskTimer(this, 0L, 1L);
    }

    private void summonWave(Player p) {
        if (!isAllowed(p) || onCooldown(waveCooldowns, p, "wave-cooldown-seconds")) return;
        new Wave(this, p).runTaskTimer(this, 0L, 1L);
    }

    // ------------------------------------------------------------------ ender pearl lock

    void lockPearls(Player p, long millis) {
        long until = System.currentTimeMillis() + millis;
        pearlLocks.merge(p.getUniqueId(), until, Math::max);
    }

    private boolean isPearlLocked(Player p) {
        Long until = pearlLocks.get(p.getUniqueId());
        if (until == null) return false;
        if (System.currentTimeMillis() > until) {
            pearlLocks.remove(p.getUniqueId());
            return false;
        }
        return true;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPearlLaunch(ProjectileLaunchEvent e) {
        if (e.getEntity() instanceof EnderPearl pearl
                && pearl.getShooter() instanceof Player p && isPearlLocked(p)) {
            e.setCancelled(true);
        }
    }

    /** Stops the throw before it starts so the pearl isn't consumed / put on cooldown. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPearlUse(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = e.getItem();
        if (item != null && item.getType() == Material.ENDER_PEARL && isPearlLocked(e.getPlayer())) {
            e.setCancelled(true);
        }
    }

    /** Catches a pearl thrown just before the wave grabbed them. */
    @EventHandler(ignoreCancelled = true)
    public void onPearlTeleport(PlayerTeleportEvent e) {
        if (e.getCause() == PlayerTeleportEvent.TeleportCause.ENDER_PEARL && isPearlLocked(e.getPlayer())) {
            e.setCancelled(true);
        }
    }

    private Location findSpot(Player p) {
        RayTraceResult r = p.rayTraceBlocks(30, FluidCollisionMode.NEVER);
        if (r != null) {
            Location loc = r.getHitPosition().toLocation(p.getWorld());
            BlockFace face = r.getHitBlockFace();
            if (face != null) loc.add(face.getDirection().multiply(0.05));
            return loc;
        }
        Vector dir = p.getLocation().getDirection().setY(0);
        if (dir.lengthSquared() < 1.0E-4) dir = new Vector(0, 0, 1);
        return p.getLocation().add(dir.normalize().multiply(6));
    }

    // ------------------------------------------------------------------ item restriction

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (!isShell(e.getItem().getItemStack())) return;
        Entity who = e.getEntity();
        if (!(who instanceof Player p) || !isAllowed(p)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHopperPickup(InventoryPickupItemEvent e) {
        if (isShell(e.getItem().getItemStack())) e.setCancelled(true);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || isAllowed(p)) return;
        if (isShell(e.getCurrentItem()) || isShell(e.getCursor())) {
            e.setCancelled(true);
            if (isShell(e.getCurrentItem())) e.setCurrentItem(null);
            if (isShell(e.getCursor())) e.setCursor(null);
        }
    }

    // ------------------------------------------------------------------ anti-kick / safety

    @EventHandler(ignoreCancelled = true)
    public void onKick(PlayerKickEvent e) {
        if (!guard.isProtected(e.getPlayer())) return;
        String reason = e.getReason().toLowerCase();
        if (reason.contains("fly") || reason.contains("flying") || reason.contains("float")
                || reason.contains("moved too quickly") || reason.contains("invalid movement")) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFall(EntityDamageEvent e) {
        if (e.getCause() == EntityDamageEvent.DamageCause.FALL
                && e.getEntity() instanceof Player p && guard.isProtected(p)) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        guard.release(e.getPlayer());
        pearlLocks.remove(e.getPlayer().getUniqueId());
    }
}
