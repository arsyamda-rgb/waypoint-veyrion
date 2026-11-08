package com.waystones;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Model 3D waystone (ItemsAdder) lewat ItemDisplay + sihir ambient:
 * orb melayang, 2 cincin rune berputar, lingkaran sihir di tanah, aliran rune ke orb.
 */
public final class WaystoneVisuals {
    private static final BlockFace[] SIDES = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};

    private static final class Parts {
        ItemDisplay base, runes, orb, ringA, ringB, circle;
        boolean active;
        boolean dormantSeen;
        float angRing, angCircle, angOrb;
        int step;
        int surge;
        int lock;
    }

    private final WaystonePlugin plugin;
    private final Map<UUID, Parts> parts = new HashMap<>();
    private final Map<String, ItemStack> cache = new HashMap<>();
    private BukkitTask task;
    private int tick;
    private boolean enabled;
    private boolean iaLoaded;
    private double range;
    private float viewRange;

    public WaystoneVisuals(WaystonePlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        readConfig();
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::loop, 20L, 5L);
    }

    private void readConfig() {
        var c = plugin.getConfig();
        enabled = c.getBoolean("visuals.enabled", true) && ItemsAdderHook.present();
        range = Math.max(24, c.getDouble("visuals.range", 64));
        viewRange = (float) c.getDouble("visuals.view-range", 1.0);
        cache.clear();
        iaLoaded = false;
        if (c.getBoolean("visuals.enabled", true) && !ItemsAdderHook.present())
            plugin.getLogger().warning("ItemsAdder tidak ditemukan: model 3D waystone dimatikan (partikel tetap jalan).");
    }

    public void stop() {
        if (task != null) task.cancel();
        for (Parts ps : parts.values()) removeAll(ps);
        parts.clear();
    }

    public void reload() {
        for (Parts ps : parts.values()) removeAll(ps);
        parts.clear();
        readConfig();
    }

    public void remove(Waystone w) {
        Parts ps = parts.remove(w.id);
        if (ps != null) removeAll(ps);
    }

    /** Spawn / perbarui tampilan sekarang juga (dipakai setelah place / aktivasi). */
    public void refresh(Waystone w) {
        if (enabled) ensure(w);
    }

    /** Waystone "menyala lebih kencang" sementara (spin cepat + partikel lebih banyak). */
    public void surge(Waystone w, int ticks) {
        Parts ps = parts.get(w.id);
        if (ps != null) ps.surge = Math.max(ps.surge, ticks);
    }

    // ------------------------------------------------------------------ loop

    private void loop() {
        tick += 5;
        if (enabled && !iaLoaded && tick % 20 == 0) {
            ConfigurationSection b = plugin.getConfig().getConfigurationSection("visuals.parts.base");
            if (b != null && ItemsAdderHook.get(b.getString("ia")) != null) {
                // ItemsAdder baru selesai load: bangun ulang semua model dengan item aslinya
                iaLoaded = true;
                cache.clear();
                for (Parts ps : parts.values()) removeAll(ps);
                parts.clear();
            }
        }
        for (Waystone w : new ArrayList<>(plugin.manager().all())) {
            Location l = w.location();
            if (l == null) continue;
            World wd = l.getWorld();
            if (!wd.isChunkLoaded(w.x >> 4, w.z >> 4)) {
                drop(w);
                continue;
            }
            Collection<Player> near = wd.getNearbyPlayers(l, range);
            if (near.isEmpty()) {
                if (tick % 40 == 0) drop(w);
                continue;
            }
            if (enabled && tick % 10 == 0) ensure(w);
            Parts ps = parts.get(w.id);
            if (ps != null && ps.active && tick % 10 == 0) animate(ps);
            magic(w, ps, wd, near);
        }
    }

    private void drop(Waystone w) {
        Parts ps = parts.remove(w.id);
        if (ps != null) removeAll(ps);
    }

    // ------------------------------------------------------------------ spawn

    /** Item ItemsAdder kalau sudah siap; kalau belum, fallback (TIDAK di-cache supaya nanti diganti). */
    private ItemStack part(String key) {
        ItemStack c = cache.get(key);
        if (c != null) return c;
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("visuals.parts." + key);
        ItemStack it = s == null ? null : ItemsAdderHook.get(s.getString("ia"));
        if (it != null) {
            cache.put(key, it);
            return it;
        }
        Material m = Material.matchMaterial(plugin.getConfig().getString("visuals.fallback-material", "PAPER"));
        it = new ItemStack(m == null ? Material.PAPER : m);
        ItemMeta im = it.getItemMeta();
        int cmd = s == null ? 0 : s.getInt("cmd", 0);
        if (cmd > 0) im.setCustomModelData(cmd);
        it.setItemMeta(im);
        return it;
    }

    /** Laser kustom. null kalau model 3D mati (pemanggil pakai partikel). */
    public Beam beam(Location bottom, float width) {
        if (!enabled) return null;
        return Beam.spawn(plugin, part("beam"), bottom, width);
    }

    /** Laporan untuk /waystone debug. */
    public List<String> debug() {
        List<String> out = new ArrayList<>();
        out.add("ItemsAdder: " + (ItemsAdderHook.present() ? "ada" : "TIDAK ADA") + ", model 3D aktif: " + enabled
                + ", IA siap: " + iaLoaded);
        ConfigurationSection ps = plugin.getConfig().getConfigurationSection("visuals.parts");
        if (ps != null) {
            for (String k : ps.getKeys(false)) {
                String id = ps.getString(k + ".ia");
                boolean ok = ItemsAdderHook.get(id) != null;
                out.add(k + ": " + id + " -> " + (ok ? "OK (pakai ItemsAdder)" : "TIDAK KETEMU (fallback cmd " + ps.getInt(k + ".cmd") + ")"));
            }
        }
        out.add("Waystone aktif di memori: " + plugin.manager().all().size() + ", model tampil: " + parts.size());
        return out;
    }

    private static Location at(World w, Waystone s, double dy) {
        return new Location(w, s.x + 0.5, s.y + dy, s.z + 0.5);
    }

    private ItemDisplay spawn(Location loc, ItemStack item, boolean glow, float scale) {
        return loc.getWorld().spawn(loc, ItemDisplay.class, d -> {
            d.setItemStack(item);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setPersistent(false);
            d.setViewRange(viewRange);
            if (glow) d.setBrightness(new Display.Brightness(15, 15));
            d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
                    new Vector3f(scale), new Quaternionf()));
        });
    }

    private static boolean valid(ItemDisplay d) {
        return d != null && d.isValid();
    }

    private void ensure(Waystone w) {
        Location l = w.location();
        if (l == null) return;
        World wd = l.getWorld();
        boolean wantActive = w.active && w.name != null;

        Parts ps = parts.get(w.id);
        if (ps == null || !valid(ps.base)) {
            if (ps != null) removeAll(ps);
            ps = new Parts();
            ps.dormantSeen = !wantActive;
            ps.base = spawn(at(wd, w, 0.5), part("base"), false, 1f);
            parts.put(w.id, ps);
        }
        ps.base.setBrightness(light(l.getBlock()));

        if (wantActive) {
            boolean ok = ps.active && valid(ps.runes) && valid(ps.orb) && valid(ps.ringA)
                    && valid(ps.ringB) && valid(ps.circle);
            if (!ok) buildActive(w, ps, ps.dormantSeen);
        } else if (ps.active) {
            removeActive(ps);
            ps.dormantSeen = true;
        }
    }

    private void buildActive(Waystone w, Parts ps, boolean ignite) {
        removeActive(ps);
        World wd = w.location().getWorld();
        float k = ignite ? 0.01f : 1f;
        ps.runes = spawn(at(wd, w, 0.5), part("runes"), true, k);
        ps.orb = spawn(at(wd, w, 1.95), part("orb"), true, k);
        ps.ringA = spawn(at(wd, w, 1.0), part("ring"), true, k);
        ps.ringB = spawn(at(wd, w, 1.45), part("ring"), true, ignite ? 0.01f : 0.75f);
        ps.circle = spawn(at(wd, w, 0.03), part("circle"), true, ignite ? 0.01f : 1.6f);
        ps.active = true;
        ps.dormantSeen = false;
        if (ignite) {
            Parts f = ps;
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (valid(f.runes)) xf(f.runes, 0, 0, 0, 0, 1f, 30);
                if (valid(f.orb)) xf(f.orb, 0, 0, 0, 0, 1f, 30);
                if (valid(f.ringA)) xf(f.ringA, 0, 0, 0, 0, 1f, 30);
                if (valid(f.ringB)) xf(f.ringB, 0, 0, 0, 0, 0.75f, 30);
                if (valid(f.circle)) xf(f.circle, 0, 0, 0, 0, 1.6f, 30);
            }, 2L);
        }
    }

    private static void xf(ItemDisplay d, float tx, float ty, float tz, float angle, float scale, int dur) {
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(dur);
        d.setTransformation(new Transformation(new Vector3f(tx, ty, tz), new Quaternionf().rotateY(angle),
                new Vector3f(scale), new Quaternionf()));
    }

    private static Display.Brightness light(Block b) {
        Block m = b.getRelative(0, 1, 0);
        int bl = m.getLightFromBlocks();
        int sl = m.getLightFromSky();
        for (BlockFace f : SIDES) {
            Block n = m.getRelative(f);
            bl = Math.max(bl, n.getLightFromBlocks());
            sl = Math.max(sl, n.getLightFromSky());
        }
        return new Display.Brightness(bl, sl);
    }

    private void removeActive(Parts ps) {
        for (ItemDisplay d : new ItemDisplay[]{ps.runes, ps.orb, ps.ringA, ps.ringB, ps.circle})
            if (d != null && d.isValid()) d.remove();
        ps.runes = ps.orb = ps.ringA = ps.ringB = ps.circle = null;
        ps.active = false;
    }

    private void removeAll(Parts ps) {
        removeActive(ps);
        if (ps.base != null && ps.base.isValid()) ps.base.remove();
        ps.base = null;
    }

    /**
     * Animasi saat waystone diklik: orb naik + membesar, cincin rune mengembang dan berputar kencang,
     * lingkaran sihir berdenyut, pilar cahaya naik, lalu semuanya turun tenang lagi.
     * Return false kalau animasi sebelumnya masih jalan.
     */
    public boolean playInteract(Waystone w) {
        Location l = w.location();
        if (l == null) return false;
        World wd = l.getWorld();
        Parts ps = parts.get(w.id);
        if (ps != null && ps.lock > 0) return false;
        double x = w.x + 0.5, y = w.y, z = w.z + 0.5;
        Location c = new Location(wd, x, y + 1.5, z);

        wd.playSound(c, Sound.BLOCK_BEACON_POWER_SELECT, 1.0f, 1.7f);
        wd.playSound(c, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1.0f, 1.2f);
        wd.playSound(c, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.2f, 1.0f);

        if (ps != null && ps.active) {
            final Parts f = ps;
            f.lock = 30;
            f.surge = Math.max(f.surge, 60);
            f.angRing += 2.4f;
            f.angOrb += 2.4f;
            f.angCircle += 1.2f;
            if (valid(f.orb)) xf(f.orb, 0, 0.5f, 0, f.angOrb, 1.7f, 8);
            if (valid(f.ringA)) xf(f.ringA, 0, 0, 0, f.angRing, 1.4f, 8);
            if (valid(f.ringB)) xf(f.ringB, 0, 0, 0, -f.angRing * 0.8f, 1.1f, 8);
            if (valid(f.circle)) xf(f.circle, 0, 0, 0, f.angCircle, 2.1f, 8);
            if (valid(f.runes)) xf(f.runes, 0, 0, 0, 0, 1.05f, 6);

            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                f.angRing += 1.5f;
                f.angOrb += 1.5f;
                f.angCircle += 0.8f;
                if (valid(f.orb)) xf(f.orb, 0, 0.2f, 0, f.angOrb, 1.15f, 18);
                if (valid(f.ringA)) xf(f.ringA, 0, 0, 0, f.angRing, 1f, 18);
                if (valid(f.ringB)) xf(f.ringB, 0, 0, 0, -f.angRing * 0.8f, 0.75f, 18);
                if (valid(f.circle)) xf(f.circle, 0, 0, 0, f.angCircle, 1.6f, 18);
                if (valid(f.runes)) xf(f.runes, 0, 0, 0, 0, 1f, 10);
            }, 8L);
        }

        final Beam ib = (ps != null && ps.active) ? beam(new Location(wd, x, y + 2.3, z), 0.8f) : null;
        new BukkitRunnable() {
            int i = 0;

            @Override
            public void run() {
                if (i == 8) {
                    Fx.p(wd, Particle.FLASH, x, y + 1.95, z, 1, 0, 0, 0, 0, null, false);
                    Fx.p(wd, Particle.END_ROD, x, y + 1.95, z, 50, 0.3, 0.3, 0.3, 0.35, null, false);
                    Fx.p(wd, Particle.ELECTRIC_SPARK, x, y + 1.95, z, 25, 0.5, 0.5, 0.5, 0.3, null, false);
                    wd.playSound(c, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 1.0f, 1.6f);
                    if (ib != null) {
                        ib.grow(0.8f, 22f, 6);
                        plugin.getServer().getScheduler().runTaskLater(plugin, () -> ib.fade(14), 8L);
                    } else {
                        Fx.beam(wd, x, y + 2.3, z, 22, 0.2, false);
                    }
                }
                if (i < 24) {
                    double rad = 0.6 + i * 0.12;
                    Fx.ring(wd, x, y + 0.1, z, rad, 20 + i, i % 2 == 0 ? Fx.CYAN : Fx.VIOLET, false);
                }
                for (int k = 0; k < 2; k++) {
                    double a = i * 0.6 + k * Math.PI;
                    double yy = (i * 0.09) % 2.4;
                    Fx.p(wd, Particle.END_ROD, x + Math.cos(a) * 0.85, y + yy, z + Math.sin(a) * 0.85,
                            1, 0, 0, 0, 0, null, false);
                    Fx.p(wd, Particle.DUST, x + Math.cos(a + 0.5) * 1.1, y + yy + 0.2, z + Math.sin(a + 0.5) * 1.1,
                            1, 0, 0, 0, 0, k == 0 ? Fx.CYAN : Fx.VIOLET, false);
                }
                ThreadLocalRandom r = ThreadLocalRandom.current();
                for (int k = 0; k < 4; k++) {
                    double a = r.nextDouble(Math.PI * 2);
                    Fx.p(wd, Particle.ENCHANT, x, y + 1.95, z, 0, Math.cos(a) * 2.0,
                            r.nextDouble(-1.2, 0.4), Math.sin(a) * 2.0, 1.0, null, false);
                }
                if (++i > 28) cancel();
            }
        }.runTaskTimer(plugin, 0L, 1L);
        return true;
    }

    // ------------------------------------------------------------------ animasi

    private void animate(Parts ps) {
        if (ps.lock > 0) {
            ps.lock -= 10;
            return;
        }
        boolean surge = ps.surge > 0;
        if (surge) ps.surge = Math.max(0, ps.surge - 10);
        ps.step++;
        ps.angRing += surge ? 1.4f : 0.785f;
        ps.angOrb += surge ? 1.6f : 1.05f;
        ps.angCircle += surge ? 0.5f : 0.196f;
        float bob = (ps.step % 2 == 0) ? 0.06f : -0.06f;
        if (valid(ps.orb)) xf(ps.orb, 0, bob, 0, ps.angOrb, surge ? 1.25f : 1f, 10);
        if (valid(ps.ringA)) xf(ps.ringA, 0, 0, 0, ps.angRing, 1f, 10);
        if (valid(ps.ringB)) xf(ps.ringB, 0, 0, 0, -ps.angRing * 0.8f, 0.75f, 10);
        if (valid(ps.circle)) xf(ps.circle, 0, 0, 0, ps.angCircle, 1.6f, 10);
    }

    // ------------------------------------------------------------------ sihir ambient

    private void magic(Waystone w, Parts ps, World wd, Collection<Player> near) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double x = w.x + 0.5, y = w.y, z = w.z + 0.5;
        boolean active = w.active && w.name != null;
        if (!active) {
            if (tick % 20 == 0)
                Fx.p(wd, Particle.SOUL_FIRE_FLAME, x, y + 1.3, z, 1, 0.25, 0.4, 0.25, 0.01, null, false);
            return;
        }
        boolean surge = ps != null && ps.surge > 0;
        int streams = surge ? 5 : 2;
        for (int i = 0; i < streams; i++) {
            double a = r.nextDouble(Math.PI * 2);
            Fx.p(wd, Particle.ENCHANT, x, y + 1.95, z, 0,
                    Math.cos(a) * 1.8, r.nextDouble(-1.2, 0.4), Math.sin(a) * 1.8, 1.0, null, false);
        }
        if (tick % 10 == 0)
            Fx.p(wd, Particle.END_ROD, x, y + 2.4, z, 1, 0.15, 0.1, 0.15, 0.02, null, false);
        if (surge && tick % 10 == 0)
            Fx.p(wd, Particle.ELECTRIC_SPARK, x, y + 1.95, z, 6, 0.5, 0.5, 0.5, 0.2, null, false);
        if ((tick + (w.id.hashCode() & 31) * 5) % 100 == 0) pulse(wd, x, y + 0.08, z);
        for (Player pl : near) {
            if (pl.getLocation().distanceSquared(new Location(wd, x, y, z)) < 12.25 && r.nextInt(30) == 0) {
                wd.playSound(new Location(wd, x, y + 1, z), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.7f,
                        (float) r.nextDouble(1.1, 2.0));
                Fx.p(wd, Particle.ENCHANT, x, y + 1.5, z, 12, 0.6, 0.6, 0.6, 0.5, null, false);
                break;
            }
        }
    }

    private void pulse(World wd, double x, double y, double z) {
        new BukkitRunnable() {
            int i = 0;

            @Override
            public void run() {
                double rad = 0.7 + i * 0.18;
                Fx.ring(wd, x, y, z, rad, 20 + i, i % 2 == 0 ? Fx.CYAN : Fx.VIOLET, false);
                if (++i > 12) cancel();
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }
}
