package com.waystones;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Teleport sinematik:
 *  DEPART  : kamera spectator mengorbit clone pemain, rune menyala, pilar cahaya menembus langit.
 *  TRANSIT : kilat putih, pemain dipindah ke atas waystone tujuan.
 *  ARRIVE  : kamera terjun menukik dari langit, pilar cahaya turun, petir, kamera mendarat di posisi pemain.
 * Sneak = lewati. Semua kondisi pemain (gamemode, posisi) dipulihkan walau terjadi error / quit.
 */
public final class TeleportSequence extends BukkitRunnable {
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private enum Phase {DEPART, TRANSIT, ARRIVE}

    private final WaystonePlugin plugin;
    private final Player p;
    private final Waystone from;
    private final Waystone target;
    private final Location origin;
    private final Location dest;
    private final Location eye0;
    private final boolean wasInvulnerable;
    private final GameMode wasMode;
    private final boolean cinematic;
    private final boolean skippable;
    private final int depTicks;
    private final int arrTicks;
    private final double arrHeight;

    private CloneNpc clone;
    private CinematicCamera cam;
    private Beam depBeam;
    private Beam arrBeam;
    private boolean depBeamTried, arrBeamTried;
    private Phase phase = Phase.DEPART;
    private int t;
    private int transitWait;
    private double depH = 12.5;
    private double arrH;
    private boolean started, done, skip, moved, titled;

    private TeleportSequence(WaystonePlugin plugin, Player p, Waystone from, Waystone target, Location dest) {
        this.plugin = plugin;
        this.p = p;
        this.from = from;
        this.target = target;
        this.origin = p.getLocation().clone();
        this.eye0 = p.getEyeLocation().clone();
        this.dest = dest;
        this.wasInvulnerable = p.isInvulnerable();
        this.wasMode = p.getGameMode();
        var cfg = plugin.getConfig();
        this.cinematic = cfg.getBoolean("cinematic.enabled", true);
        this.skippable = cfg.getBoolean("cinematic.allow-skip", true);
        this.depTicks = Math.max(40, cfg.getInt("cinematic.departure-ticks", 110));
        this.arrTicks = cinematic ? Math.max(40, cfg.getInt("cinematic.arrival-ticks", 110)) : 40;
        this.arrHeight = cfg.getDouble("cinematic.arrival-height", 36);
        this.arrH = arrHeight;
    }

    public static void begin(WaystonePlugin plugin, Player p, Waystone from, Waystone to) {
        if (plugin.sequences().containsKey(p.getUniqueId())) return;
        Location d = plugin.manager().arrivalSpot(to);
        if (d == null) {
            p.sendMessage(MM.deserialize("<red>Dunia tujuan tidak ditemukan."));
            return;
        }
        new TeleportSequence(plugin, p, from, to, d).start();
    }

    public void requestSkip() {
        if (skippable) skip = true;
    }

    public boolean releasing() {
        return cam != null && cam.releasing();
    }

    public boolean frozen() {
        return phase != Phase.ARRIVE;
    }

    // ------------------------------------------------------------------ start / end

    private void start() {
        plugin.sequences().put(p.getUniqueId(), this);
        p.setInvulnerable(true);
        if (p.isInsideVehicle()) p.leaveVehicle();
        for (Player o : Bukkit.getOnlinePlayers()) if (!o.equals(p)) o.hidePlayer(plugin, p);
        PlayerRestore.save(plugin, p, origin, wasMode);
        clone = CloneNpc.spawn(p, origin);
        if (cinematic) {
            depH = Math.max(3.0, Math.min(12.5, clearance(origin.clone().add(0, 1, 0), 14) - 1.5));
            cam = new CinematicCamera(plugin, p);
            cam.attach(eye0);
        }
        if (from != null) plugin.visuals().surge(from, depTicks + 20);
        started = true;
        runTaskTimer(plugin, 0L, 1L);
    }

    /** Selesai. abort=true kalau dibatalkan / error: pemain dikembalikan ke tempat aman. */
    void end(boolean abort) {
        if (done) return;
        done = true;
        if (started) {
            try {
                cancel();
            } catch (IllegalStateException ignored) {
            }
        }
        plugin.sequences().remove(p.getUniqueId());
        if (depBeam != null) {
            depBeam.remove();
            depBeam = null;
        }
        if (arrBeam != null) {
            arrBeam.fade(10);
            arrBeam = null;
        }
        if (clone != null) {
            clone.remove();
            clone = null;
        }
        if (cam != null) {
            cam.detach();
            cam = null;
        }
        for (Player o : Bukkit.getOnlinePlayers()) if (!o.equals(p)) o.showPlayer(plugin, p);
        if (!p.isOnline()) return; // data pemulihan tetap ada di PDC, dipakai saat join
        p.removePotionEffect(PotionEffectType.BLINDNESS);
        p.setGameMode(wasMode);
        Location to = (!abort || moved) ? dest : origin;
        try {
            p.teleport(to);
        } catch (Throwable ex) {
            plugin.getLogger().warning("Teleport akhir gagal: " + ex);
        }
        p.setFallDistance(0);
        p.setInvulnerable(wasInvulnerable);
        PlayerRestore.clear(plugin, p);
        if (!abort) finalFx();
    }

    public void finish() {
        end(true);
    }

    @Override
    public void run() {
        if (!p.isOnline()) {
            end(true);
            return;
        }
        try {
            switch (phase) {
                case DEPART -> depart();
                case TRANSIT -> transit();
                case ARRIVE -> arrive();
            }
        } catch (Throwable ex) {
            plugin.getLogger().warning("Sequence error: " + ex);
            end(true);
        }
    }

    // ------------------------------------------------------------------ DEPART

    private void depart() {
        World w = origin.getWorld();
        int T = depTicks;
        if (skip && t < T) t = T;
        double u = Math.min(1.0, (double) t / T);
        double ox = origin.getX(), oy = origin.getY(), oz = origin.getZ();
        ThreadLocalRandom r = ThreadLocalRandom.current();

        if (t == 0) {
            snd(origin, Sound.BLOCK_BEACON_POWER_SELECT, 1.2f, 0.8f);
            snd(origin, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.0f, 0.7f);
            snd(origin, Sound.ENTITY_EVOKER_PREPARE_SUMMON, 0.8f, 1.1f);
            if (skippable && cam != null) p.sendActionBar(MM.deserialize("<gray>Sneak <dark_gray>untuk melewati"));
        }
        if (t == (int) (T * 0.30)) {
            snd(origin, Sound.BLOCK_BEACON_AMBIENT, 1.5f, 1.8f);
            snd(origin, Sound.BLOCK_CONDUIT_ACTIVATE, 1.0f, 1.2f);
        }
        if (t == (int) (T * 0.50)) snd(origin, Sound.ENTITY_WARDEN_SONIC_CHARGE, 1.6f, 1.2f);
        if (t == (int) (T * 0.56)) snd(origin, Sound.ENTITY_WARDEN_SONIC_BOOM, 1.2f, 1.4f);
        if (t % 10 == 0 && t < T)
            snd(origin, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.2f, (float) (0.7 + 1.3 * u));

        if (t >= T) {
            burstAndLeave();
            return;
        }

        if (cam != null) cam.move(departCam(u));

        double lift = 1.8 * smooth(0, 1, u);
        Location cl = origin.clone().add(0, lift, 0);
        cl.setYaw((float) (origin.getYaw() + 720.0 * u * u));
        clone.move(cl);

        // helix 3 lengan
        double rad = 1.5 - 0.7 * u;
        for (int k = 0; k < 3; k++) {
            double a = t * 0.45 + k * (2 * Math.PI / 3);
            double y = (t % 24) / 24.0 * 2.4;
            double px = ox + Math.cos(a) * rad, pz = oz + Math.sin(a) * rad;
            Fx.p(w, Particle.DUST, px, oy + y, pz, 1, 0, 0, 0, 0, k == 0 ? Fx.CYAN : Fx.VIOLET, false);
            Fx.p(w, Particle.END_ROD, px, oy + y, pz, 1, 0, 0, 0, 0, null, false);
        }
        // cincin rune di tanah
        if (t % 8 == 0)
            Fx.ring(w, ox, oy + 0.1, oz, 0.8 + ((t % 40) / 8.0) * 0.35, 28, (t / 8) % 2 == 0 ? Fx.CYAN : Fx.VIOLET, false);
        // glyph mengalir ke pemain
        for (int i = 0; i < 3; i++) {
            double a = r.nextDouble(Math.PI * 2);
            Fx.p(w, Particle.ENCHANT, ox, oy + 1.0 + lift, oz, 0,
                    Math.cos(a) * 2.5, r.nextDouble(-0.8, 1.2), Math.sin(a) * 2.5, 1.0, null, false);
        }
        // pilar cahaya
        if (t >= T * 0.5) {
            if (!depBeamTried) {
                depBeamTried = true;
                depBeam = plugin.visuals().beam(origin.clone().add(0, 1.0, 0), 1.3f);
                if (depBeam != null) depBeam.grow(1.3f, 70f, (int) (T * 0.32));
            }
            if (depBeam == null) {
                double bu = smooth(T * 0.5, T * 0.85, t);
                Fx.beam(w, ox, oy + lift + 1.0, oz, 70 * bu, 0.3, true);
            }
            Fx.p(w, Particle.ELECTRIC_SPARK, ox, oy + lift + 1, oz, 6, 0.4, 0.8, 0.4, 0.25, null, false);
        }
        if (t >= T - 6) Fx.p(w, Particle.FLASH, ox, oy + 2.0, oz, 1, 0, 0, 0, 0, null, false);
        t++;
    }

    private void burstAndLeave() {
        World w = origin.getWorld();
        Location mid = origin.clone().add(0, 2.2, 0);
        w.spawnParticle(Particle.FLASH, mid, 1);
        w.spawnParticle(Particle.FIREWORK, mid, 100, 0.3, 0.7, 0.3, 0.3);
        w.spawnParticle(Particle.END_ROD, mid, 80, 0.3, 0.9, 0.3, 0.45);
        w.spawnParticle(Particle.REVERSE_PORTAL, mid, 100, 0.4, 0.9, 0.4, 0.6);
        snd(mid, Sound.ENTITY_ENDERMAN_TELEPORT, 1.5f, 0.6f);
        snd(mid, Sound.BLOCK_BEACON_DEACTIVATE, 1.2f, 1.5f);
        snd(mid, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.8f, 1.3f);
        if (depBeam != null) {
            depBeam.fade(6);
            depBeam = null;
        }
        if (clone != null) {
            clone.remove();
            clone = null;
        }
        p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 90, 0, false, false, false));
        phase = Phase.TRANSIT;
        transitWait = 0;
        if (cam != null) cam.detach();
        Location start = cinematic ? arriveCam(0) : dest;
        p.teleportAsync(start).thenAccept(ok -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (done) {
                if (ok && p.isOnline()) p.teleportAsync(dest);
                return;
            }
            if (!ok) {
                p.sendMessage(MM.deserialize("<red>Teleport gagal."));
                end(true);
                return;
            }
            moved = true;
            p.setFallDistance(0);
            phase = Phase.ARRIVE;
            t = 0;
            if (cinematic) {
                arrH = Math.max(6.0, Math.min(arrHeight,
                        clearance(dest.clone().add(0, 1.62, 0), arrHeight + 2) - 1.5));
                if (cam == null) cam = new CinematicCamera(plugin, p);
                cam.attach(arriveCam(0));
            }
        }));
    }

    private void transit() {
        if (++transitWait > 200) {
            p.sendMessage(MM.deserialize("<red>Teleport terlalu lama, dibatalkan."));
            end(true);
        }
    }

    // ------------------------------------------------------------------ ARRIVE

    private void arrive() {
        World w = dest.getWorld();
        int T = arrTicks;
        if (skip && t < T) t = T;
        if (t >= T) {
            end(false);
            return;
        }
        double u = (double) t / T;
        Vector f = focus();
        double fx = f.getX(), fz = f.getZ();
        double gy = target.y + 1.0;
        int impact = (int) (0.30 * T);

        if (cam != null) cam.move(arriveCam(u));
        if (t == 14) p.removePotionEffect(PotionEffectType.BLINDNESS);

        if (t == 0) {
            plugin.visuals().surge(target, T + 40);
            snd(f.toLocation(w), Sound.BLOCK_END_PORTAL_SPAWN, 0.8f, 1.5f);
            snd(f.toLocation(w), Sound.BLOCK_BEACON_ACTIVATE, 1.4f, 1.3f);
            snd(f.toLocation(w), Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 1.0f, 1.2f);
        }

        // pilar turun dari langit lalu memudar
        if (!arrBeamTried) {
            arrBeamTried = true;
            arrBeam = plugin.visuals().beam(new Location(w, fx, gy, fz), 1.6f);
            if (arrBeam != null) arrBeam.descend(1.6f, 70f, impact);
        }
        if (arrBeam != null) {
            if (t == (int) (0.72 * T)) {
                arrBeam.fade((int) (0.25 * T));
                arrBeam = null;
            }
        } else {
            double head = smooth(0, impact, t);
            double bottomY = (gy + 70) + (gy - (gy + 70)) * head;
            double topY = gy + 70 - 70 * smooth(0.72 * T, 0.97 * T, t);
            if (topY > bottomY) Fx.beam(w, fx, bottomY, fz, topY - bottomY, 0.32, true);
        }

        if (t == impact) {
            Location g = new Location(w, fx, gy, fz);
            w.strikeLightningEffect(g);
            Fx.p(w, Particle.FLASH, fx, gy + 1, fz, 1, 0, 0, 0, 0, null, true);
            Fx.p(w, Particle.TOTEM_OF_UNDYING, fx, gy + 1, fz, 70, 0.6, 0.9, 0.6, 0.35, null, true);
            Fx.p(w, Particle.FIREWORK, fx, gy + 1, fz, 90, 0.4, 0.8, 0.4, 0.3, null, true);
            snd(g, Sound.ITEM_TRIDENT_THUNDER, 1.2f, 1.2f);
            snd(g, Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 1.4f);
            snd(g, Sound.BLOCK_BEACON_POWER_SELECT, 1.4f, 1.6f);
        }
        int k = t - impact;
        if (k >= 0 && k < 30) {
            double rr = 0.5 + k * 0.4;
            Fx.ring(w, fx, gy + 0.1, fz, rr, (int) (rr * 10) + 16, k % 2 == 0 ? Fx.CYAN : Fx.VIOLET, true);
        }
        // pusaran rune
        if (t > impact && t < 0.9 * T) {
            for (int i = 0; i < 4; i++) {
                double a = t * 0.5 + i * (Math.PI / 2);
                double y = ((t * 0.6) + i * 3) % 12;
                Fx.p(w, Particle.END_ROD, fx + Math.cos(a) * 2.2, gy + y, fz + Math.sin(a) * 2.2, 1, 0, 0, 0, 0, null, true);
            }
        }
        if (t == (int) (0.5 * T)) showTitle();
        if (t == T - 1) {
            Fx.p(w, Particle.END_ROD, dest.getX(), dest.getY() + 1, dest.getZ(), 40, 0.4, 0.8, 0.4, 0.2, null, true);
            Fx.ring(w, dest.getX(), dest.getY() + 0.1, dest.getZ(), 1.2, 28, Fx.CYAN, true);
        }
        t++;
    }

    private void showTitle() {
        titled = true;
        p.showTitle(Title.title(
                MM.deserialize("<gradient:#00f5ff:#a855f7><bold>✦ BERHASIL TELEPORT ✦</bold></gradient>"),
                MM.deserialize("<gold><bold>" + target.name + "</bold>"),
                Title.Times.times(Duration.ofMillis(400), Duration.ofMillis(2600), Duration.ofMillis(800))));
    }

    private void finalFx() {
        World w = dest.getWorld();
        Location mid = dest.clone().add(0, 1, 0);
        w.spawnParticle(Particle.TOTEM_OF_UNDYING, mid, 40, 0.4, 0.8, 0.4, 0.3);
        Fx.ring(w, dest.getX(), dest.getY() + 0.1, dest.getZ(), 1.2, 28, Fx.CYAN, false);
        Fx.ring(w, dest.getX(), dest.getY() + 0.1, dest.getZ(), 2.0, 36, Fx.VIOLET, false);
        w.playSound(mid, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        if (!titled) showTitle();
    }

    // ------------------------------------------------------------------ kamera

    private Location departCam(double u) {
        World w = origin.getWorld();
        double yawR = Math.toRadians(origin.getYaw());
        double alpha = Math.atan2(Math.cos(yawR), -Math.sin(yawR));
        double theta = alpha + Math.PI + 2 * Math.PI * u;
        double R = 3.4 + 2.8 * u;
        double H = 1.3 + (depH - 1.3) * u * u;
        Vector orbit = new Vector(origin.getX() + Math.cos(theta) * R, origin.getY() + H,
                origin.getZ() + Math.sin(theta) * R);
        double s = smooth(0, 0.3, u);
        Vector pos = eye0.toVector().multiply(1 - s).add(orbit.multiply(s));
        Vector tgt = new Vector(origin.getX(), origin.getY() + 0.9 + (depH - 1.0) * 0.8 * smooth(0.35, 1.0, u),
                origin.getZ());
        float[] a = angles(tgt.subtract(pos));
        float yaw = lerpAngle(eye0.getYaw(), a[0], (float) s);
        float pitch = eye0.getPitch() + (a[1] - eye0.getPitch()) * (float) s;
        return new Location(w, pos.getX(), pos.getY(), pos.getZ(), yaw, pitch);
    }

    private Location arriveCam(double u) {
        World w = dest.getWorld();
        Vector f = focus();
        double relx = dest.getX() - f.getX(), relz = dest.getZ() - f.getZ();
        double thetaEnd = Math.atan2(relz, relx);
        double rEnd = Math.max(0.6, Math.hypot(relx, relz));
        double inv = (1 - u) * (1 - u);
        double theta = thetaEnd - Math.PI * 2 * 1.1 * inv;
        double r = rEnd + (16 - rEnd) * Math.pow(inv, 1.1);
        double y = dest.getY() + 1.62 + arrH * Math.pow(inv, 1.3);
        Vector pos = new Vector(f.getX() + Math.cos(theta) * r, y, f.getZ() + Math.sin(theta) * r);
        float[] a = angles(f.clone().subtract(pos));
        float b = (float) smooth(0.7, 1.0, u);
        float yaw = lerpAngle(a[0], dest.getYaw(), b);
        float pitch = a[1] + (0f - a[1]) * b;
        return new Location(w, pos.getX(), pos.getY(), pos.getZ(), yaw, pitch);
    }

    private Vector focus() {
        return new Vector(target.x + 0.5, target.y + 1.9, target.z + 0.5);
    }

    // ------------------------------------------------------------------ util

    private static double clearance(Location from, double max) {
        try {
            RayTraceResult res = from.getWorld().rayTraceBlocks(from, new Vector(0, 1, 0), max,
                    FluidCollisionMode.NEVER, true);
            return res == null ? max : res.getHitPosition().distance(from.toVector());
        } catch (Throwable t) {
            return max;
        }
    }

    private static double smooth(double a, double b, double x) {
        double v = Math.max(0, Math.min(1, (x - a) / (b - a)));
        return v * v * (3 - 2 * v);
    }

    private static float[] angles(Vector d) {
        double len = d.length();
        if (len < 1e-6) return new float[]{0f, 0f};
        float yaw = (float) Math.toDegrees(Math.atan2(-d.getX(), d.getZ()));
        float pitch = (float) -Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, d.getY() / len))));
        return new float[]{yaw, pitch};
    }

    private static float lerpAngle(float a, float b, float t) {
        float d = ((b - a + 540f) % 360f) - 180f;
        return a + d * t;
    }

    /** Suara untuk orang lain di sekitar + suara personal untuk pemain (kamera bisa jauh dari titik suara). */
    private void snd(Location at, Sound s, float vol, float pitch) {
        World w = at.getWorld();
        for (Player o : w.getPlayers()) {
            if (!o.equals(p) && o.getLocation().distanceSquared(at) < 48 * 48) o.playSound(at, s, vol, pitch);
        }
        if (p.isOnline()) p.playSound(p.getLocation(), s, vol * 0.9f, pitch);
    }
}
