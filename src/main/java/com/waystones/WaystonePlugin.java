package com.waystones;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class WaystonePlugin extends JavaPlugin implements TabExecutor {
    public static final MiniMessage MM = MiniMessage.miniMessage();
    private WaystoneManager manager;
    private WaystoneVisuals visuals;
    private final Map<UUID, TeleportSequence> sequences = new ConcurrentHashMap<>();

    public WaystoneManager manager() {
        return manager;
    }

    public WaystoneVisuals visuals() {
        return visuals;
    }

    public Map<UUID, TeleportSequence> sequences() {
        return sequences;
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        manager = new WaystoneManager(this);
        manager.load();
        getServer().getPluginManager().registerEvents(new WaystoneListener(this), this);
        getCommand("waystone").setExecutor(this);
        getCommand("waystone").setTabCompleter(this);

        // model 3D + sihir ambient
        visuals = new WaystoneVisuals(this);
        visuals.start();

        try {
            FakePlayer.check();
            getLogger().info("NPC packet bawaan aktif (skin + armor asli).");
        } catch (Throwable t) {
            getLogger().warning("NPC packet tidak kompatibel dengan versi server ini, pakai ArmorStand: " + t);
        }
    }

    @Override
    public void onDisable() {
        new ArrayList<>(sequences.values()).forEach(TeleportSequence::finish);
        if (visuals != null) visuals.stop();
        if (manager != null) manager.save();
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (a.length == 0) {
            s.sendMessage(MM.deserialize("<gray>/waystone <white>give [player] | register | remove | list | reload | tp <nama>"));
            return true;
        }
        switch (a[0].toLowerCase()) {
            case "give" -> {
                Player t = a.length > 1 ? Bukkit.getPlayerExact(a[1]) : (s instanceof Player pl ? pl : null);
                if (t == null) {
                    s.sendMessage(MM.deserialize("<red>Pemain tidak ditemukan."));
                    return true;
                }
                t.getInventory().addItem(manager.createItem());
                s.sendMessage(MM.deserialize("<green>Waystone diberikan ke " + t.getName() + "."));
            }
            case "register" -> {
                if (!(s instanceof Player pl)) return true;
                Block b = pl.getTargetBlockExact(6);
                if (b == null || b.getType().isAir()) {
                    s.sendMessage(MM.deserialize("<red>Lihat ke block yang mau dijadikan waystone."));
                } else if (manager.get(b) != null) {
                    s.sendMessage(MM.deserialize("<red>Block ini sudah waystone."));
                } else {
                    manager.add(b, pl.getUniqueId(), false);
                    s.sendMessage(MM.deserialize("<green>Block didaftarkan. Klik kanan untuk aktivasi."));
                }
            }
            case "remove" -> {
                if (!(s instanceof Player pl)) return true;
                Block b = pl.getTargetBlockExact(6);
                Waystone w = b == null ? null : manager.getAny(b);
                if (w == null) {
                    s.sendMessage(MM.deserialize("<red>Bukan waystone."));
                } else {
                    Location dl = w.location();
                    boolean body = w.fromItem;
                    manager.remove(w);
                    if (body) {
                        manager.clearBody(w);
                        if (dl != null) dl.getWorld().dropItemNaturally(dl.add(0.5, 0.5, 0.5), manager.createItem());
                        s.sendMessage(MM.deserialize("<green>Waystone dihapus, item dikembalikan."));
                    } else {
                        s.sendMessage(MM.deserialize("<green>Registrasi waystone dihapus (block tetap)."));
                    }
                }
            }
            case "list" -> {
                s.sendMessage(MM.deserialize("<gray>Waystone aktif: <white>" + manager.active().size()));
                for (Waystone w : manager.active())
                    s.sendMessage(MM.deserialize("<dark_gray>- <white>" + w.name + " <gray>(" + w.world + " "
                            + w.x + " " + w.y + " " + w.z + ")"));
            }
            case "reload" -> {
                reloadConfig();
                manager.load();
                visuals.reload();
                s.sendMessage(MM.deserialize("<green>Reload selesai."));
            }
            case "debug" -> {
                for (String line : visuals.debug()) s.sendMessage(MM.deserialize("<gray>" + line.replace("<", "\\<")));
            }
            case "tp" -> {
                if (!(s instanceof Player pl)) return true;
                if (a.length < 2) {
                    s.sendMessage(MM.deserialize("<red>/waystone tp <nama waystone>"));
                    return true;
                }
                String name = String.join(" ", java.util.Arrays.copyOfRange(a, 1, a.length));
                Waystone t = null;
                for (Waystone w : manager.active()) if (w.name.equalsIgnoreCase(name)) t = w;
                if (t == null) {
                    s.sendMessage(MM.deserialize("<red>Waystone tidak ditemukan."));
                    return true;
                }
                TeleportSequence.begin(this, pl, null, t);
            }
            default -> s.sendMessage(MM.deserialize("<red>Subcommand tidak dikenal."));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        if (a.length == 1) return List.of("give", "register", "remove", "list", "reload", "tp", "debug");
        if (a.length >= 2 && a[0].equalsIgnoreCase("tp")) {
            List<String> l = new ArrayList<>();
            for (Waystone w : manager.active()) l.add(w.name);
            return l;
        }
        return List.of();
    }
}
