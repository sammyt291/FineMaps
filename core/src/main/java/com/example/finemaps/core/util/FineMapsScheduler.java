package com.example.finemaps.core.util;

import com.example.finemaps.core.nms.NMSAdapterFactory;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Scheduling utilities that work on both Bukkit/Paper and Folia.
 *
 * <p>On Folia, Bukkit's legacy scheduler methods throw {@link UnsupportedOperationException}, so we
 * route work through Folia schedulers. Per user request we detect Folia via
 * {@code Bukkit.getVersion().contains("Folia")}.</p>
 *
 * <p>This project is a single JAR: Folia/Paper-only APIs must only be invoked when running on Folia.</p>
 */
public final class FineMapsScheduler {

    private FineMapsScheduler() {
    }

    /**
     * Wrapper around either a Folia ScheduledTask or a BukkitTask.
     */
    public static class Task {
        private final Object foliaTask;
        private final BukkitTask bukkitTask;

        public Task(Object foliaTask) {
            this.foliaTask = foliaTask;
            this.bukkitTask = null;
        }

        public Task(BukkitTask bukkitTask) {
            this.bukkitTask = bukkitTask;
            this.foliaTask = null;
        }

        public void cancel() {
            if (foliaTask != null) {
                // Route cancellation on Folia. Avoid exposing Paper types in fields/signatures.
                try {
                    // This cast is only resolved if executed.
                    ((io.papermc.paper.threadedregions.scheduler.ScheduledTask) foliaTask).cancel();
                    return;
                } catch (Throwable ignored) {
                }
                try {
                    foliaTask.getClass().getMethod("cancel").invoke(foliaTask);
                    return;
                } catch (Throwable ignored) {
                }
            }
            if (bukkitTask != null) {
                try {
                    bukkitTask.cancel();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * Determine whether we're running on Folia.
     *
     * <p>Do not rely solely on {@code Bukkit.getVersion()} string contents; some builds may omit
     * the literal "Folia" even when Folia threading/schedulers are present. Use several signals:
     * server name, version string (case-insensitive), presence of Folia classes, and presence of
     * Folia scheduler accessors.</p>
     */
    public static boolean isFolia() {
        // Fast path: server name
        try {
            String name = Bukkit.getName();
            if (name != null && name.equalsIgnoreCase("Folia")) return true;
        } catch (Throwable ignored) {
        }
        try {
            if (Bukkit.getServer() != null) {
                String name = Bukkit.getServer().getName();
                if (name != null && name.equalsIgnoreCase("Folia")) return true;
            }
        } catch (Throwable ignored) {
        }

        // Version string (case-insensitive)
        try {
            String v = Bukkit.getVersion();
            if (v != null && v.toLowerCase().contains("folia")) return true;
        } catch (Throwable ignored) {
        }

        // Presence of Folia classes
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException ignored) {
        } catch (Throwable ignored) {
        }
        try {
            Class.forName("io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler");
            return true;
        } catch (ClassNotFoundException ignored) {
        } catch (Throwable ignored) {
        }

        // Presence of Folia scheduler accessors on Bukkit
        try {
            Bukkit.class.getMethod("getGlobalRegionScheduler");
            return true;
        } catch (NoSuchMethodException ignored) {
        } catch (Throwable ignored) {
        }

        return false;
    }

    public static void runSync(Plugin plugin, Runnable runnable) {
        if (plugin == null || runnable == null) return;

        if (isFolia()) {
            try {
                Bukkit.getGlobalRegionScheduler().run(plugin, ignored -> runnable.run());
                return;
            } catch (Throwable ignored) {
                // fall through
            }
        }
        Bukkit.getScheduler().runTask(plugin, runnable);
    }

    public static void runSyncDelayed(Plugin plugin, Runnable runnable, long delayTicks) {
        if (plugin == null || runnable == null) return;
        if (delayTicks < 0) delayTicks = 0;

        if (isFolia()) {
            try {
                Bukkit.getGlobalRegionScheduler().runDelayed(plugin, ignored -> runnable.run(), delayTicks);
                return;
            } catch (Throwable ignored) {
                // fall through
            }
        }
        Bukkit.getScheduler().runTaskLater(plugin, runnable, delayTicks);
    }

    public static void runAsync(Plugin plugin, Runnable runnable) {
        if (plugin == null || runnable == null) return;

        if (isFolia()) {
            try {
                Bukkit.getAsyncScheduler().runNow(plugin, ignored -> runnable.run());
                return;
            } catch (Throwable ignored) {
                // fall through
            }
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, runnable);
    }

    public static void runForEntity(Plugin plugin, Entity entity, Runnable runnable) {
        if (plugin == null || runnable == null) return;

        if (isFolia() && entity != null) {
            try {
                entity.getScheduler().run(plugin, ignored -> runnable.run(), () -> {
                });
                return;
            } catch (Throwable ignored) {
                // fall through
            }
        }
        runSync(plugin, runnable);
    }

    public static void runForEntityDelayed(Plugin plugin, Entity entity, Runnable runnable, long delayTicks) {
        if (plugin == null || runnable == null) return;
        if (delayTicks < 0) delayTicks = 0;

        if (isFolia() && entity != null) {
            try {
                entity.getScheduler().runDelayed(plugin, ignored -> runnable.run(), () -> {
                }, delayTicks);
                return;
            } catch (Throwable ignored) {
                // fall through
            }
        }
        runSyncDelayed(plugin, runnable, delayTicks);
    }

    /**
     * Tick-based repeating work. On Folia uses global region scheduler; elsewhere uses Bukkit scheduler.
     */
    public static Task runSyncRepeating(Plugin plugin, Runnable runnable, long initialDelayTicks, long periodTicks) {
        if (plugin == null || runnable == null) return null;
        if (initialDelayTicks < 0) initialDelayTicks = 0;
        if (periodTicks < 1) periodTicks = 1;

        if (isFolia()) {
            try {
                Object folia = Bukkit.getGlobalRegionScheduler()
                    .runAtFixedRate(plugin, ignored -> runnable.run(), initialDelayTicks, periodTicks);
                return new Task(folia);
            } catch (Throwable ignored) {
                // Fallback: chain runDelayed (more robust across scheduler API drift)
                Task chained = chainGlobalDelayed(plugin, runnable, initialDelayTicks, periodTicks);
                if (chained != null) return chained;
                return null;
            }
        }

        return new Task(Bukkit.getScheduler().runTaskTimer(plugin, runnable, initialDelayTicks, periodTicks));
    }

    /**
     * Tick-based repeating work scoped to an entity. On Folia uses entity scheduler; elsewhere uses global repeating.
     */
    public static Task runForEntityRepeating(Plugin plugin,
                                             Entity entity,
                                             Runnable runnable,
                                             long initialDelayTicks,
                                             long periodTicks) {
        if (plugin == null || runnable == null) return null;
        if (initialDelayTicks < 0) initialDelayTicks = 0;
        if (periodTicks < 1) periodTicks = 1;

        if (isFolia() && entity != null) {
            try {
                Object folia = entity.getScheduler()
                    .runAtFixedRate(plugin, ignored -> runnable.run(), () -> {
                    }, initialDelayTicks, periodTicks);
                return new Task(folia);
            } catch (Throwable ignored) {
                Task chained = chainEntityDelayed(plugin, entity, runnable, initialDelayTicks, periodTicks);
                if (chained != null) return chained;
                return null;
            }
        }

        return runSyncRepeating(plugin, runnable, initialDelayTicks, periodTicks);
    }

    public static void cancel(Task task) {
        if (task == null) return;
        task.cancel();
    }

    /**
     * Backwards compatibility: cancel any task handle.
     */
    public static void cancel(Object taskHandle) {
        if (taskHandle == null) return;
        if (taskHandle instanceof Task) {
            ((Task) taskHandle).cancel();
            return;
        }
        if (taskHandle instanceof BukkitTask) {
            try {
                ((BukkitTask) taskHandle).cancel();
            } catch (Throwable ignored) {
            }
            return;
        }
        try {
            taskHandle.getClass().getMethod("cancel").invoke(taskHandle);
        } catch (Throwable ignored) {
        }
    }

    public static void runLaterWallClock(Runnable runnable, long delay, TimeUnit unit) {
        if (runnable == null) return;
        if (delay < 0) delay = 0;
        if (unit == null) unit = TimeUnit.MILLISECONDS;
        java.util.concurrent.CompletableFuture.delayedExecutor(delay, unit).execute(runnable);
    }

    private static Task chainGlobalDelayed(Plugin plugin, Runnable runnable, long initialDelayTicks, long periodTicks) {
        if (plugin == null || runnable == null) return null;
        if (!isFolia() && !NMSAdapterFactory.isFolia()) return null;

        AtomicBoolean cancelled = new AtomicBoolean(false);
        final Object[] current = new Object[] {null};
        final Consumer<Object>[] runner = new Consumer[1];
        runner[0] = ignoredTask -> {
            if (cancelled.get()) return;
            try {
                runnable.run();
            } catch (Throwable ignored2) {
            }
            if (cancelled.get()) return;
            try {
                // Cast to raw Consumer to avoid hard-referencing Paper types here.
                current[0] = Bukkit.getGlobalRegionScheduler().runDelayed(plugin, (Consumer) runner[0], periodTicks);
            } catch (Throwable ignored2) {
                cancelled.set(true);
            }
        };

        try {
            current[0] = Bukkit.getGlobalRegionScheduler().runDelayed(plugin, (Consumer) runner[0], initialDelayTicks);
        } catch (Throwable ignored) {
            return null;
        }

        return new Task(current[0]) {
            @Override
            public void cancel() {
                cancelled.set(true);
                FineMapsScheduler.cancel(current[0]);
            }
        };
    }

    private static Task chainEntityDelayed(Plugin plugin, Entity entity, Runnable runnable, long initialDelayTicks, long periodTicks) {
        if (plugin == null || entity == null || runnable == null) return null;
        if (!isFolia() && !NMSAdapterFactory.isFolia()) return null;

        AtomicBoolean cancelled = new AtomicBoolean(false);
        final Object[] current = new Object[] {null};
        final Consumer<Object>[] runner = new Consumer[1];
        runner[0] = ignoredTask -> {
            if (cancelled.get()) return;
            try {
                if (entity.isDead() || !entity.isValid()) {
                    cancelled.set(true);
                    return;
                }
            } catch (Throwable ignored2) {
            }
            try {
                runnable.run();
            } catch (Throwable ignored2) {
            }
            if (cancelled.get()) return;
            try {
                current[0] = entity.getScheduler().runDelayed(plugin, (Consumer) runner[0], () -> {
                }, periodTicks);
            } catch (Throwable ignored2) {
                cancelled.set(true);
            }
        };

        try {
            current[0] = entity.getScheduler().runDelayed(plugin, (Consumer) runner[0], () -> {
            }, initialDelayTicks);
        } catch (Throwable ignored) {
            return null;
        }

        return new Task(current[0]) {
            @Override
            public void cancel() {
                cancelled.set(true);
                FineMapsScheduler.cancel(current[0]);
            }
        };
    }
}

