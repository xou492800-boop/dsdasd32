package dev.whirl;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Temporarily grants allowFlight so the server's "Flying is not enabled" check
 * never kicks someone who is being spun around or launched. The previous state
 * is restored once the player lands (or after a hard timeout).
 */
final class FlightGuard {

    private static final class State {
        boolean prevAllow;
        long until;
    }

    private final Map<UUID, State> states = new HashMap<>();

    void protect(Player p, long millis) {
        long until = System.currentTimeMillis() + millis;
        State s = states.get(p.getUniqueId());
        if (s == null) {
            s = new State();
            s.prevAllow = p.getAllowFlight();
            states.put(p.getUniqueId(), s);
        }
        s.until = Math.max(s.until, until);
        if (!p.getAllowFlight()) {
            p.setAllowFlight(true);
        }
    }

    boolean isProtected(Player p) {
        return states.containsKey(p.getUniqueId());
    }

    /** Call every few ticks. */
    void tick() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, State>> it = states.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, State> e = it.next();
            State s = e.getValue();
            if (now < s.until) continue;
            Player p = Bukkit.getPlayer(e.getKey());
            // wait for landing, but never longer than 4s past the deadline
            if (p != null && p.isOnline() && !p.isOnGround() && now < s.until + 4000) continue;
            restore(p, s);
            it.remove();
        }
    }

    void release(Player p) {
        State s = states.remove(p.getUniqueId());
        if (s != null) restore(p, s);
    }

    void restoreAll() {
        for (Map.Entry<UUID, State> e : states.entrySet()) {
            restore(Bukkit.getPlayer(e.getKey()), e.getValue());
        }
        states.clear();
    }

    private void restore(Player p, State s) {
        if (p == null || !p.isOnline()) return;
        if (!s.prevAllow) {
            p.setFlying(false);
            p.setAllowFlight(false);
        }
    }
}
