package com.waystones;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import com.destroystokyo.paper.event.player.PlayerStopSpectatingEntityEvent;
import org.bukkit.block.BlockFace;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import static com.waystones.WaystonePlugin.MM;

public final class WaystoneListener implements Listener {
    private static final Pattern NAME = Pattern.compile("[\\p{L}\\p{N} _'\\-]+");
    private static final String AKTIF =
            "<gradient:#00f5ff:#a855f7:#ff4ecd><bold>✦ WAYSTONE AKTIF ✦</bold></gradient>";

    private final WaystonePlugin plugin;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    private record Pending(Waystone ws, BukkitTask timeout) {
    }

    public WaystoneListener(WaystonePlugin plugin) {
        this.plugin = plugin;
    }

    // ---------- place / break ----------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (!plugin.manager().isWaystoneItem(e.getItemInHand())) return;
        Player p = e.getPlayer();
        if (!p.hasPermission("waystone.place")) {
            e.setCancelled(true);
            return;
        }
        Block placed = e.getBlockPlaced();
        boolean tall = placed.getType() == Material.BARRIER;
        Block up = placed.getRelative(BlockFace.UP);
        if (tall && !up.getType().isAir()) {
            e.setCancelled(true);
            p.sendActionBar(MM.deserialize("<red>Butuh 2 blok kosong ke atas untuk waystone."));
            return;
        }
        Waystone w = plugin.manager().add(placed, p.getUniqueId(), true);
        if (tall) up.setType(Material.BARRIER, false);
        plugin.visuals().refresh(w);
        Location c = placed.getLocation().add(0.5, 0.2, 0.5);
        c.getWorld().playSound(c, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1f, 0.8f);
        c.getWorld().playSound(c, Sound.BLOCK_BEACON_POWER_SELECT, 0.6f, 1.5f);
        Fx.ring(c.getWorld(), c.getX(), c.getY(), c.getZ(), 1.3, 30, Fx.CYAN, false);
        p.sendActionBar(MM.deserialize("<gray>Waystone terpasang. <yellow>Klik kanan</yellow> untuk aktivasi."));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Waystone w = plugin.manager().getAny(e.getBlock());
        if (w == null) return;
        Player p = e.getPlayer();
        if (!p.hasPermission("waystone.break")) {
            e.setCancelled(true);
            p.sendActionBar(MM.deserialize("<red>Kamu tidak boleh menghancurkan waystone."));
            return;
        }
        Location drop = w.location();
        plugin.manager().remove(w);
        if (w.fromItem) {
            e.setDropItems(false);
            plugin.manager().clearBody(w);
            if (drop != null && p.getGameMode() != GameMode.CREATIVE)
                drop.getWorld().dropItemNaturally(drop.add(0.5, 0.5, 0.5), plugin.manager().createItem());
        }
        p.sendMessage(MM.deserialize("<gray>Waystone" + (w.name != null ? " <white>" + w.name : "") + "<gray> dihapus."));
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(b -> plugin.manager().getAny(b) != null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(b -> plugin.manager().getAny(b) != null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        if (e.getBlocks().stream().anyMatch(b -> plugin.manager().getAny(b) != null)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        if (e.getBlocks().stream().anyMatch(b -> plugin.manager().getAny(b) != null)) e.setCancelled(true);
    }

    // ---------- interact ----------

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block b = e.getClickedBlock();
        if (b == null) return;
        Waystone w = plugin.manager().getAny(b);
        if (w == null) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;

        Player p = e.getPlayer();
        if (plugin.sequences().containsKey(p.getUniqueId())) return;

        if (!w.active || w.name == null) {
            startNaming(p, w);
        } else {
            if (!p.hasPermission("waystone.use")) {
                p.sendActionBar(MM.deserialize("<red>Kamu tidak punya izin memakai waystone."));
                return;
            }
            boolean played = plugin.visuals().playInteract(w);
            if (played) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (p.isOnline() && !plugin.sequences().containsKey(p.getUniqueId())
                            && plugin.manager().get(w.block()) == w)
                        new WaystoneGui(plugin, w).open(p);
                }, 12L);
            } else {
                new WaystoneGui(plugin, w).open(p);
            }
        }
    }

    // ---------- naming ----------

    private void startNaming(Player p, Waystone w) {
        if (!p.hasPermission("waystone.activate")) {
            p.sendActionBar(MM.deserialize("<red>Kamu tidak boleh mengaktifkan waystone."));
            return;
        }
        for (Map.Entry<UUID, Pending> en : pending.entrySet()) {
            if (en.getValue().ws().id.equals(w.id) && !en.getKey().equals(p.getUniqueId())) {
                p.sendActionBar(MM.deserialize("<red>Waystone ini sedang diberi nama pemain lain."));
                return;
            }
        }
        cancelPending(p.getUniqueId());
        int secs = plugin.getConfig().getInt("naming-timeout-seconds", 60);
        BukkitTask timeout = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (pending.remove(p.getUniqueId()) != null)
                p.sendMessage(MM.deserialize("<red>Waktu habis. Klik kanan waystone lagi untuk mengulang."));
        }, secs * 20L);
        pending.put(p.getUniqueId(), new Pending(w, timeout));
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 1.5f);
        p.sendActionBar(MM.deserialize("<yellow>Ketik <white>nama waystone</white> di chat"));
        p.sendMessage(MM.deserialize("<gradient:#00f5ff:#a855f7><bold>Beri nama waystone ini.</bold></gradient>"
                + "<gray> Ketik di chat (" + plugin.getConfig().getInt("max-name-length", 24)
                + " huruf maks), atau <red>batal</red>."));
    }

    private void cancelPending(UUID id) {
        Pending pd = pending.remove(id);
        if (pd != null) pd.timeout().cancel();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        if (!pending.containsKey(p.getUniqueId())) return;
        e.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        Bukkit.getScheduler().runTask(plugin, () -> handleName(p, text));
    }

    private void handleName(Player p, String text) {
        Pending pd = pending.get(p.getUniqueId());
        if (pd == null) return;
        if (text.equalsIgnoreCase("batal")) {
            cancelPending(p.getUniqueId());
            p.sendMessage(MM.deserialize("<gray>Aktivasi dibatalkan."));
            return;
        }
        Waystone w = pd.ws();
        if (plugin.manager().get(w.block()) != w) {
            cancelPending(p.getUniqueId());
            p.sendMessage(MM.deserialize("<red>Waystone sudah tidak ada."));
            return;
        }
        int max = plugin.getConfig().getInt("max-name-length", 24);
        if (text.length() < 3 || text.length() > max || !NAME.matcher(text).matches()) {
            p.sendMessage(MM.deserialize("<red>Nama harus 3-" + max + " karakter, hanya huruf, angka, spasi, _ ' -. Coba lagi."));
            return;
        }
        if (plugin.manager().nameTaken(text)) {
            p.sendMessage(MM.deserialize("<red>Nama sudah dipakai waystone lain. Coba nama lain."));
            return;
        }
        cancelPending(p.getUniqueId());
        activate(p, w, text);
    }

    private void activate(Player p, Waystone w, String name) {
        w.name = name;
        w.active = true;
        plugin.manager().save();

        Location c = w.location().add(0.5, 0.5, 0.5);
        World world = c.getWorld();
        world.playSound(c, Sound.BLOCK_BEACON_ACTIVATE, 1.5f, 1.2f);
        world.playSound(c, Sound.ITEM_TRIDENT_THUNDER, 0.7f, 1.5f);
        world.playSound(c, Sound.BLOCK_END_PORTAL_SPAWN, 0.6f, 1.7f);
        world.playSound(c, Sound.ENTITY_ILLUSIONER_CAST_SPELL, 1f, 1.2f);
        world.strikeLightningEffect(c);
        world.spawnParticle(Particle.FLASH, c.clone().add(0, 1, 0), 1);
        world.spawnParticle(Particle.TOTEM_OF_UNDYING, c.clone().add(0, 1, 0), 80, 0.5, 0.9, 0.5, 0.3);
        plugin.visuals().refresh(w);
        plugin.visuals().surge(w, 120);

        final Beam ab = plugin.visuals().beam(c.clone().add(0, 0.4, 0), 1.0f);
        if (ab != null) {
            ab.grow(1.0f, 40f, 10);
            Bukkit.getScheduler().runTaskLater(plugin, () -> ab.fade(14), 24L);
        }
        new BukkitRunnable() {
            int i = 0;

            @Override
            public void run() {
                for (int k = 0; k < 2; k++) {
                    double a = i * 0.5 + k * Math.PI;
                    world.spawnParticle(Particle.END_ROD,
                            c.clone().add(Math.cos(a) * 0.9, i * 0.12, Math.sin(a) * 0.9), 1, 0, 0, 0, 0);
                }
                if (ab == null && i % 2 == 0) Fx.beam(world, c.getX(), c.getY(), c.getZ(), 30, 0.25, false);
                Fx.ring(world, c.getX(), c.getY() - 0.4, c.getZ(), 0.6 + i * 0.12, 24,
                        i % 2 == 0 ? Fx.CYAN : Fx.VIOLET, false);
                if (++i > 30) cancel();
            }
        }.runTaskTimer(plugin, 0L, 1L);

        new BukkitRunnable() {
            int n = 0;

            @Override
            public void run() {
                if (!p.isOnline() || n++ >= 4) {
                    cancel();
                    return;
                }
                p.sendActionBar(MM.deserialize(AKTIF));
            }
        }.runTaskTimer(plugin, 0L, 15L);

        p.showTitle(Title.title(net.kyori.adventure.text.Component.empty(),
                MM.deserialize("<gold><bold>" + name + "</bold>"),
                Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1800), Duration.ofMillis(500))));
        p.sendMessage(MM.deserialize("<gray>Waystone <white>" + name + "<gray> aktif."));
    }

    // ---------- GUI ----------

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof WaystoneGui gui)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() != e.getInventory()) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;

        int slot = e.getRawSlot();
        if (slot == 45 && gui.page() > 0) {
            gui.setPage(gui.page() - 1);
            return;
        }
        if (slot == 53 && gui.hasNext()) {
            gui.setPage(gui.page() + 1);
            return;
        }
        Waystone t = gui.at(slot);
        if (t == null || t.id.equals(gui.origin().id)) return;

        p.closeInventory();
        Block tb = t.block();
        if (!t.active || tb == null || plugin.manager().get(tb) != t) {
            p.sendMessage(MM.deserialize("<red>Waystone tujuan sudah tidak aktif."));
            return;
        }
        TeleportSequence.begin(plugin, p, gui.origin(), t);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof WaystoneGui) e.setCancelled(true);
    }

    // ---------- sinematik: pengaman ----------

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onStopSpectate(PlayerStopSpectatingEntityEvent e) {
        TeleportSequence s = plugin.sequences().get(e.getPlayer().getUniqueId());
        if (s == null || s.releasing()) return;
        e.setCancelled(true);
        s.requestSkip();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSpectateTp(PlayerTeleportEvent e) {
        if (e.getCause() == PlayerTeleportEvent.TeleportCause.SPECTATE
                && plugin.sequences().containsKey(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (plugin.getConfig().getBoolean("cinematic.block-commands", true)
                && plugin.sequences().containsKey(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline() && !plugin.sequences().containsKey(p.getUniqueId())) PlayerRestore.apply(plugin, p);
        }, 5L);
    }

    // ---------- freeze & cleanup ----------

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        TeleportSequence s = plugin.sequences().get(e.getPlayer().getUniqueId());
        if (s != null && s.frozen() && e.hasChangedPosition()) e.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        cancelPending(id);
        TeleportSequence s = plugin.sequences().get(id);
        if (s != null) s.finish();
    }
}
