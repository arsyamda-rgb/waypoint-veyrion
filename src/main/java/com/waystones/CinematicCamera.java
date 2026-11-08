package com.waystones;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Kamera sinematik: entity display tak terlihat yang di-spectate pemain (mode SPECTATOR),
 * plus dua bar hitam letterbox. Posisi kamera digeser tiap tick dengan interpolasi client.
 */
final class CinematicCamera {
    private static final double BAR_DIST = 0.25;

    private final WaystonePlugin plugin;
    private final Player p;
    private final int smooth;
    private final boolean letterbox;
    private final float barFrac;

    private ItemDisplay cam;
    private BlockDisplay top;
    private BlockDisplay bottom;
    private BukkitTask bindTask;
    private volatile boolean releasing;

    CinematicCamera(WaystonePlugin plugin, Player p) {
        this.plugin = plugin;
        this.p = p;
        var cfg = plugin.getConfig();
        this.smooth = Math.max(1, Math.min(20, cfg.getInt("cinematic.smoothing-ticks", 3)));
        this.letterbox = cfg.getBoolean("cinematic.letterbox", false);
        this.barFrac = (float) cfg.getDouble("cinematic.letterbox-size", 0.12);
    }

    boolean releasing() {
        return releasing;
    }

    /** Pasang kamera di loc, masuk spectator, lalu bind ke kamera beberapa tick kemudian. */
    void attach(Location loc) {
        removeEntities();
        if (p.getGameMode() != GameMode.SPECTATOR) p.setGameMode(GameMode.SPECTATOR);
        cam = loc.getWorld().spawn(loc, ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.setVisibleByDefault(false);
            d.setTeleportDuration(smooth);
        });
        p.showEntity(plugin, cam);
        if (letterbox) {
            top = bar(loc, true);
            bottom = bar(loc, false);
        }
        bindTask = Bukkit.getScheduler().runTaskLater(plugin, this::bind, 4L);
    }

    private void bind() {
        if (cam == null || !cam.isValid() || !p.isOnline()) return;
        try {
            p.setSpectatorTarget(cam);
        } catch (Throwable t) {
            plugin.getLogger().warning("Gagal bind kamera: " + t);
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (cam != null && cam.isValid() && p.isOnline() && p.getGameMode() == GameMode.SPECTATOR
                    && p.getSpectatorTarget() != cam) {
                try {
                    p.setSpectatorTarget(cam);
                } catch (Throwable ignored) {
                }
            }
        }, 6L);
    }

    private BlockDisplay bar(Location loc, boolean upper) {
        BlockDisplay d = loc.getWorld().spawn(loc, BlockDisplay.class, e -> {
            e.setBlock(Bukkit.createBlockData(Material.BLACK_CONCRETE));
            e.setPersistent(false);
            e.setVisibleByDefault(false);
            e.setBillboard(Display.Billboard.CENTER);
            e.setTeleportDuration(smooth);
            e.setBrightness(new Display.Brightness(15, 15));
            float half = (float) (BAR_DIST * Math.tan(Math.toRadians(35)));
            float inner = half * (1f - 2f * barFrac);
            float y0 = upper ? inner : -inner - 1f;
            e.setTransformation(new Transformation(new Vector3f(-2f, y0, -0.005f), new Quaternionf(),
                    new Vector3f(4f, 1f, 0.01f), new Quaternionf()));
        });
        p.showEntity(plugin, d);
        return d;
    }

    /** Geser kamera (dan bar letterbox) ke loc. */
    void move(Location l) {
        if (cam == null || !cam.isValid()) return;
        cam.teleport(l);
        if (top != null && bottom != null) {
            Vector dir = l.getDirection().normalize().multiply(BAR_DIST);
            Location at = l.clone().add(dir);
            top.teleport(at);
            bottom.teleport(at);
        }
    }

    /** Lepas spectate + hapus entity. Gamemode TIDAK dikembalikan di sini. */
    void detach() {
        if (bindTask != null) {
            bindTask.cancel();
            bindTask = null;
        }
        releasing = true;
        try {
            if (p.isOnline() && p.getGameMode() == GameMode.SPECTATOR && p.getSpectatorTarget() != null) {
                p.setSpectatorTarget(null);
            }
        } catch (Throwable ignored) {
        } finally {
            releasing = false;
        }
        removeEntities();
    }

    private void removeEntities() {
        for (Entity e : new Entity[]{cam, top, bottom}) {
            if (e != null && e.isValid()) e.remove();
        }
        cam = null;
        top = null;
        bottom = null;
    }
}
