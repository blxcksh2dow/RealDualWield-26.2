package beer.devs.realdualwield;

import org.bukkit.Bukkit;

import java.util.logging.Logger;

/**
 * Tiny debug logger: with {@code debug: true} in config.yml the plugin explains, step by step,
 * what it does with every off-hand attack (useful to understand why an attack is skipped).
 */
public final class Debug
{
    private static boolean enabled;

    private Debug()
    {
    }

    static void setEnabled(boolean enabled)
    {
        Debug.enabled = enabled;
    }

    public static boolean isEnabled()
    {
        return enabled;
    }

    public static void log(String message)
    {
        if (!enabled)
            return;

        Logger logger = Main.inst != null ? Main.inst.getLogger() : Bukkit.getLogger();
        logger.info("[RealDualWield][debug] " + message);
    }
}
