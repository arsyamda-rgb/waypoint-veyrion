package com.waystones;

import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.World;

/** Helper partikel. Semua lewat satu pintu supaya gampang diatur. */
final class Fx {
    static final Particle.DustOptions CYAN = new Particle.DustOptions(Color.fromRGB(110, 225, 240), 1.5f);
    static final Particle.DustOptions VIOLET = new Particle.DustOptions(Color.fromRGB(225, 190, 115), 1.5f);
    static final Particle.DustOptions WHITE = new Particle.DustOptions(Color.WHITE, 2.0f);
    static final Particle.DustTransition CV =
            new Particle.DustTransition(Color.fromRGB(120, 225, 240), Color.fromRGB(235, 250, 250), 1.6f);

    private Fx() {
    }

    static void p(World w, Particle pt, double x, double y, double z, int n,
                  double dx, double dy, double dz, double speed, Object data, boolean force) {
        w.spawnParticle(pt, x, y, z, n, dx, dy, dz, speed, data, force);
    }

    static void ring(World w, double cx, double cy, double cz, double r, int pts,
                     Particle.DustOptions opt, boolean force) {
        for (int i = 0; i < pts; i++) {
            double a = i * (2 * Math.PI / pts);
            p(w, Particle.DUST, cx + Math.cos(a) * r, cy, cz + Math.sin(a) * r, 1, 0, 0, 0, 0, opt, force);
        }
    }

    /** Pilar cahaya dari yBottom naik setinggi height. */
    static void beam(World w, double x, double yBottom, double z, double height, double radius, boolean force) {
        for (double y = 0; y < height; y += 4.0) {
            double seg = Math.min(4.0, height - y);
            double cy = yBottom + y + seg / 2;
            p(w, Particle.END_ROD, x, cy, z, 3, radius, seg / 3, radius, 0.0, null, force);
            p(w, Particle.DUST_COLOR_TRANSITION, x, cy, z, 6, radius, seg / 3, radius, 0.0, CV, force);
        }
    }
}
