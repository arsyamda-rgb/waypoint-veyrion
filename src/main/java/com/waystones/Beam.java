package com.waystones;

import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Laser / pilar cahaya kustom: model ItemsAdder (4 bidang silang + inti) dengan texture animasi
 * yang di-scale tinggi lewat ItemDisplay. Tumbuh, turun dari langit, dan memudar dengan interpolasi client.
 */
public final class Beam {
    private final WaystonePlugin plugin;
    private final ItemDisplay d;
    private float angle;
    private float height = 0.01f;
    private boolean removed;

    private Beam(WaystonePlugin plugin, ItemDisplay d) {
        this.plugin = plugin;
        this.d = d;
    }

    static Beam spawn(WaystonePlugin plugin, ItemStack item, Location bottom, float width) {
        ItemDisplay disp = bottom.getWorld().spawn(bottom, ItemDisplay.class, e -> {
            e.setItemStack(item);
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            e.setPersistent(false);
            e.setViewRange(4f);
            e.setBrightness(new Display.Brightness(15, 15));
            e.setTransformation(new Transformation(new Vector3f(0, 0.005f, 0), new Quaternionf(),
                    new Vector3f(width, 0.01f, width), new Quaternionf()));
        });
        return new Beam(plugin, disp);
    }

    private void apply(float w, float h, float bottomY, int ticks) {
        if (removed || !d.isValid()) return;
        angle += 0.9f;
        height = h;
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(ticks);
        d.setTransformation(new Transformation(new Vector3f(0, bottomY + h / 2f, 0),
                new Quaternionf().rotateY(angle), new Vector3f(w, h, w), new Quaternionf()));
    }

    /** Tumbuh naik dari dasar sampai setinggi h. */
    public void grow(float w, float h, int ticks) {
        apply(w, h, 0f, ticks);
    }

    /** Turun dari ketinggian h sampai menyentuh dasar (ujung atas tetap di h). */
    public void descend(float w, float h, int ticks) {
        apply(w, 0.01f, h, 0);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> apply(w, h, 0f, ticks), 2L);
    }

    /** Menipis lalu hilang. */
    public void fade(int ticks) {
        apply(0.05f, height, 0f, ticks);
        plugin.getServer().getScheduler().runTaskLater(plugin, this::remove, ticks + 2L);
    }

    public void remove() {
        if (removed) return;
        removed = true;
        if (d.isValid()) d.remove();
    }
}
