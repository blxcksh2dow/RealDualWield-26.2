package beer.devs.realdualwield;

import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityEvent;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps MMOItems from charging the <b>main hand</b> weapon when the player hits with the off hand.
 *
 * <p>Why it is needed, read from the MythicLib and MMOItems sources (26.x):
 * <pre>
 *   // MythicLib, DamageManager#findAttack, for a plain EntityDamageByEntityEvent
 *   StatProvider attacker = StatProvider.get((LivingEntity) damager, EquipmentSlot.MAIN_HAND, true);
 *   new MeleeAttackMetadata(new DamageMetadata(event.getDamage(), getVanillaDamageTypes(event, MAIN_HAND)), ...);
 * </pre>
 * A damage event is <b>always</b> a main hand attack for MythicLib (its own comment says that
 * "left-hand attacks are handled by specific listeners", and there is none for this plugin), and:
 * <pre>
 *   // MMOItems, ItemUse#meleeAttacks, EventPriority.LOW, ignoreCancelled = true
 *   ItemStack used = player.getInventory().getItem(((MeleeAttackMetadata) event.getAttack()).getHand().toBukkit());
 *   new Weapon(playerData, item).handleTargetedAttack(...);   // takes the mana and the stamina
 * </pre>
 * So every off-hand hit was charged TWICE: once by this plugin (the off-hand weapon, the right
 * one) and once by MMOItems (the main hand weapon). With the same sword in both hands that is
 * exactly twice the mana, which is what was being seen in game.
 *
 * <p>And it was worse than that: when the main hand item is not a melee weapon, or when the player
 * does not meet its requirements (level, class), or when he does not have the mana for IT,
 * MMOItems calls {@code event.setCancelled(true)}. MythicLib's {@code AttackEvent#setCancelled}
 * writes straight through to the wrapped {@code EntityDamageEvent}, so the main hand was also able
 * to silently cancel the off-hand damage.
 *
 * <p>The fix hides the off-hand damage from MMOItems and from nothing else: its listener is the
 * only one at {@code LOW} with {@code ignoreCancelled = true}, so cancelling the event at
 * {@code LOWEST} skips MMOItems, and restoring it at {@code NORMAL} hands MythicLib (which fires
 * the event from its own {@code HIGHEST} listener on the damage event) an untouched event. Damage
 * types, damage modifiers and {@code PlayerKillEntityEvent} keep working exactly as before, and
 * the only weapon that pays for the hit is the one that actually hit: the off hand one.
 */
public final class MythicLibHook
{
    /** The MythicLib event MMOItems listens to. Looked up by name: MythicLib stays optional. */
    private static final String PLAYER_ATTACK_EVENT = "io.lumine.mythic.lib.api.event.PlayerAttackEvent";

    private static boolean enabled;
    private static boolean attempted;

    /** The entities that are being damaged by an off-hand hit right now. */
    private static final Set<UUID> OFFHAND =
            Collections.newSetFromMap(new ConcurrentHashMap<UUID, Boolean>());

    /** The events this plugin cancelled, so that only those are put back the way they were. */
    private static final Set<Event> CANCELLED =
            Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<Event, Boolean>()));

    private MythicLibHook()
    {
    }

    /**
     * Registers the two listeners that hide the off-hand hit from MMOItems. Without MythicLib
     * installed nothing happens: the class simply is not there.
     */
    public static void init(Plugin plugin)
    {
        attempted = true;

        if (plugin == null)
            return;

        try
        {
            Class<?> eventClass = Class.forName(PLAYER_ATTACK_EVENT);
            if (!Event.class.isAssignableFrom(eventClass) || !Cancellable.class.isAssignableFrom(eventClass))
                return;

            @SuppressWarnings("unchecked")
            Class<? extends Event> attackEvent = (Class<? extends Event>) eventClass;

            // Bukkit wants a listener instance, but all the work is done by the two executors.
            Listener listener = new Listener()
            {
            };

            // LOWEST: before MMOItems (LOW, ignoreCancelled): it never sees the off-hand hit.
            EventExecutor hide = (ignored, event) ->
            {
                if (!isOffhandDamage(event))
                    return;

                CANCELLED.add(event);
                ((Cancellable) event).setCancelled(true);
                Debug.log("mythiclib: " + entityOf(event) + " is hit by the off hand, the attack is hidden"
                        + " from MMOItems (only the off-hand weapon pays for it)");
            };

            // NORMAL: right after MMOItems has been skipped, so MythicLib finds its event intact.
            EventExecutor restore = (ignored, event) ->
            {
                if (CANCELLED.remove(event))
                    ((Cancellable) event).setCancelled(false);
            };

            Bukkit.getPluginManager().registerEvent(attackEvent, listener, EventPriority.LOWEST, hide, plugin);
            Bukkit.getPluginManager().registerEvent(attackEvent, listener, EventPriority.NORMAL, restore, plugin);

            enabled = true;
        }
        catch (Throwable t)
        {
            enabled = false;
            Debug.log("mythiclib: the off-hand hit could not be hidden from MMOItems: " + t);
        }
    }

    /** Marks the entity as being damaged by an off-hand hit: call it around the damage. */
    static void beginOffhandDamage(LivingEntity target)
    {
        if (target != null)
            OFFHAND.add(target.getUniqueId());
    }

    /** Undoes {@link #beginOffhandDamage(LivingEntity)}. */
    static void endOffhandDamage(LivingEntity target)
    {
        if (target != null)
            OFFHAND.remove(target.getUniqueId());
    }

    public static boolean isEnabled()
    {
        return enabled;
    }

    private static boolean isOffhandDamage(Event event)
    {
        return event instanceof EntityEvent
                && OFFHAND.contains(((EntityEvent) event).getEntity().getUniqueId());
    }

    private static String entityOf(Event event)
    {
        return event instanceof EntityEvent ? String.valueOf(((EntityEvent) event).getEntity().getType()) : "?";
    }

    /** One line for {@code /rdwdebug}. */
    public static List<String> describe()
    {
        List<String> lines = new ArrayList<>();
        lines.add("MythicLib: " + (enabled
                ? "found, the off-hand hit is hidden from MMOItems (the main hand weapon is never charged)"
                : (attempted ? "not found" : "not initialised")));
        return lines;
    }
}
