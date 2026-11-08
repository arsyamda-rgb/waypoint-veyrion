package com.waystones;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Method;

/** Ambil item ItemsAdder lewat reflection (tanpa dependency compile-time). */
final class ItemsAdderHook {
    private static Method getInstance;
    private static Method getItemStack;
    private static boolean tried;

    private ItemsAdderHook() {
    }

    static boolean present() {
        return Bukkit.getPluginManager().isPluginEnabled("ItemsAdder");
    }

    /** null kalau ItemsAdder tidak ada / id tidak dikenal. */
    static ItemStack get(String id) {
        if (id == null || id.isBlank() || !present()) return null;
        try {
            if (!tried) {
                tried = true;
                Class<?> c = Class.forName("dev.lone.itemsadder.api.CustomStack");
                getInstance = c.getMethod("getInstance", String.class);
                getItemStack = c.getMethod("getItemStack");
            }
            if (getInstance == null) return null;
            Object cs = getInstance.invoke(null, id);
            if (cs == null) return null;
            ItemStack it = (ItemStack) getItemStack.invoke(cs);
            return it == null ? null : it.clone();
        } catch (Throwable t) {
            return null;
        }
    }
}
