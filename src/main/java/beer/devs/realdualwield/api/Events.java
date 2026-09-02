package beer.devs.realdualwield.api;

import org.bukkit.Bukkit;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;

public class Events
{
    public static boolean call(Event event)
    {
        Bukkit.getPluginManager().callEvent(event);
        if (event instanceof Cancellable)
            return !((Cancellable) event).isCancelled();
        return true;
    }
}
