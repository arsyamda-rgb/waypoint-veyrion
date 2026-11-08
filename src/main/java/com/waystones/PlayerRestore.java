package com.waystones;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffectType;

/**
 * Jaring pengaman: kalau server crash / pemain keluar saat sinematik,
 * gamemode + posisi asli dipulihkan saat dia join lagi.
 */
final class PlayerRestore {
    private PlayerRestore() {
    }

    private static NamespacedKey key(WaystonePlugin pl) {
        return new NamespacedKey(pl, "restore");
    }

    static void save(WaystonePlugin pl, Player p, Location l, GameMode gm) {
        String s = gm.name() + "|" + l.getWorld().getName() + "|" + l.getX() + "|" + l.getY() + "|"
                + l.getZ() + "|" + l.getYaw() + "|" + l.getPitch();
        p.getPersistentDataContainer().set(key(pl), PersistentDataType.STRING, s);
    }

    static void clear(WaystonePlugin pl, Player p) {
        p.getPersistentDataContainer().remove(key(pl));
    }

    static void apply(WaystonePlugin pl, Player p) {
        String s = p.getPersistentDataContainer().get(key(pl), PersistentDataType.STRING);
        if (s == null) return;
        try {
            String[] a = s.split("\\|");
            GameMode gm = GameMode.valueOf(a[0]);
            World w = Bukkit.getWorld(a[1]);
            p.setGameMode(gm);
            p.setInvulnerable(false);
            p.removePotionEffect(PotionEffectType.BLINDNESS);
            if (w != null) {
                p.teleport(new Location(w, Double.parseDouble(a[2]), Double.parseDouble(a[3]),
                        Double.parseDouble(a[4]), Float.parseFloat(a[5]), Float.parseFloat(a[6])));
            }
            for (Player o : Bukkit.getOnlinePlayers()) if (!o.equals(p)) o.showPlayer(pl, p);
        } catch (Throwable t) {
            pl.getLogger().warning("Gagal memulihkan pemain " + p.getName() + ": " + t);
        }
        clear(pl, p);
    }
}
