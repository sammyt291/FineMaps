package com.example.finemaps.core.util;

import com.example.finemaps.core.nms.NMSAdapterFactory;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Scheduling utilities that are compatible with both Paper/Spigot and Folia.
 *
 * <p>On Folia, Bukkit's legacy scheduler methods throw {@link UnsupportedOperationException}.
 * Folia provides region/global/async schedulers instead; we access them via reflection so this
 * project can keep compiling against the standard Bukkit API.</p>
 */
public final class FineMapsScheduler {

    private FineMapsScheduler() {
    }

    /**
     * Runs a task as soon as possible on the "main" execution context.
     *
     * <p>On Folia this uses the global region scheduler. On non-Folia it uses Bukkit's scheduler.</p>
     */
    public static void runSync(Plugin plugin, Runnable runnable) {
        if (plugin == null || runnable == null) return;

        if (NMSAdapterFactory.isFolia()) {
            if (tryRunGlobal(plugin, runnable)) {
                return;
            }
        }

        BukkitScheduler scheduler = Bukkit.getScheduler();
        scheduler.runTask(plugin, runnable);
    }

    /**
     * Runs a task later (tick delay) on the "main" execution context.
     *
     * <p>On Folia this uses the global region scheduler. On non-Folia it uses Bukkit's scheduler.</p>
     */
    public static void runSyncDelayed(Plugin plugin, Runnable runnable, long delayTicks) {
        if (plugin == null || runnable == null) return;
        if (delayTicks < 0) delayTicks = 0;

        if (NMSAdapterFactory.isFolia()) {
            if (tryRunGlobalDelayed(plugin, runnable, delayTicks)) {
                return;
            }
        }

        Bukkit.getScheduler().runTaskLater(plugin, runnable, delayTicks);
    }

    /**
     * Runs a task asynchronously.
     *
     * <p>On Folia this uses the async scheduler. On non-Folia it uses Bukkit's scheduler.</p>
     */
    public static void runAsync(Plugin plugin, Runnable runnable) {
        if (plugin == null || runnable == null) return;

        if (NMSAdapterFactory.isFolia()) {
            if (tryRunAsyncNow(plugin, runnable)) {
                return;
            }
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, runnable);
    }

    /**
     * Runs a task on an entity's scheduler when available (Folia), otherwise falls back to {@link #runSync(Plugin, Runnable)}.
     *
     * <p>This is the safest option for player/entity interactions on Folia.</p>
     */
    public static void runForEntity(Plugin plugin, Entity entity, Runnable runnable) {
        if (plugin == null || runnable == null) return;

        if (NMSAdapterFactory.isFolia() && entity != null) {
            if (tryRunEntity(plugin, entity, runnable)) {
                return;
            }
        }

        runSync(plugin, runnable);
    }

    /**
     * Runs a task later using wall-clock time. This is provided as an escape hatch for situations where
     * a simple delay is needed but tick-based scheduling is not required.
     */
    public static void runLaterWallClock(Runnable runnable, long delay, TimeUnit unit) {
        if (runnable == null) return;
        if (delay < 0) delay = 0;
        if (unit == null) unit = TimeUnit.MILLISECONDS;
        java.util.concurrent.CompletableFuture.delayedExecutor(delay, unit).execute(runnable);
    }

    private static boolean tryRunGlobal(Plugin plugin, Runnable runnable) {
        try {
            Object globalRegionScheduler = Bukkit.class.getMethod("getGlobalRegionScheduler").invoke(null);
            if (globalRegionScheduler == null) return false;

            // GlobalRegionScheduler#run(Plugin, Consumer<ScheduledTask>)
            java.lang.reflect.Method run = globalRegionScheduler.getClass().getMethod(
                "run",
                Plugin.class,
                Consumer.class
            );
            Consumer<Object> consumer = ignoredTask -> runnable.run();
            run.invoke(globalRegionScheduler, plugin, consumer);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean tryRunGlobalDelayed(Plugin plugin, Runnable runnable, long delayTicks) {
        try {
            Object globalRegionScheduler = Bukkit.class.getMethod("getGlobalRegionScheduler").invoke(null);
            if (globalRegionScheduler == null) return false;

            // GlobalRegionScheduler#runDelayed(Plugin, Consumer<ScheduledTask>, long)
            java.lang.reflect.Method runDelayed = globalRegionScheduler.getClass().getMethod(
                "runDelayed",
                Plugin.class,
                Consumer.class,
                long.class
            );
            Consumer<Object> consumer = ignoredTask -> runnable.run();
            runDelayed.invoke(globalRegionScheduler, plugin, consumer, delayTicks);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean tryRunAsyncNow(Plugin plugin, Runnable runnable) {
        try {
            Object asyncScheduler = Bukkit.class.getMethod("getAsyncScheduler").invoke(null);
            if (asyncScheduler == null) return false;

            // AsyncScheduler#runNow(Plugin, Consumer<ScheduledTask>)
            java.lang.reflect.Method runNow = asyncScheduler.getClass().getMethod(
                "runNow",
                Plugin.class,
                Consumer.class
            );
            Consumer<Object> consumer = ignoredTask -> runnable.run();
            runNow.invoke(asyncScheduler, plugin, consumer);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean tryRunEntity(Plugin plugin, Entity entity, Runnable runnable) {
        try {
            // Entity#getScheduler()
            Object entityScheduler = entity.getClass().getMethod("getScheduler").invoke(entity);
            if (entityScheduler == null) return false;

            Consumer<Object> consumer = ignoredTask -> runnable.run();

            // EntityScheduler#run(Plugin, Consumer<ScheduledTask>, Runnable retired)
            try {
                java.lang.reflect.Method run = entityScheduler.getClass().getMethod(
                    "run",
                    Plugin.class,
                    Consumer.class,
                    Runnable.class
                );
                run.invoke(entityScheduler, plugin, consumer, (Runnable) () -> {
                });
                return true;
            } catch (NoSuchMethodException ignored) {
                // Older/alternate signature: EntityScheduler#run(Plugin, Consumer<ScheduledTask>)
                java.lang.reflect.Method run = entityScheduler.getClass().getMethod(
                    "run",
                    Plugin.class,
                    Consumer.class
                );
                run.invoke(entityScheduler, plugin, consumer);
                return true;
            }
        } catch (Throwable ignored) {
            return false;
        }
    }
}

