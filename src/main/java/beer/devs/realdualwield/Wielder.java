package beer.devs.realdualwield;

import beer.devs.realdualwield.api.Events;
import beer.devs.realdualwield.api.PlayerOffhandPlantBreakEvent;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

class Wielder
{
    /**
     * Two consecutive interaction events closer than this are considered a "held" right click
     * (the button was never released) instead of two separate clicks.
     */
    private static final long HOLD_THRESHOLD_MS = 120;

    public final Player player;
    private long time;
    private long lastInteractTime;
    private long lastInteractGap;
    private long lastEntityInteractTime;
    private long lastEntityInteractGap;
    private boolean usingLeftWeapon;
    private @Nullable Integer delay;
    /** Length (in ticks) of the cooldown currently running: used to scale the damage. */
    private int cooldownTotal = 12;
    /** Timestamp of the last hit dealt with the MAIN hand (-1 = never). */
    private long lastMainHandAttack = -1;
    /** Timestamp of the last hit dealt with the OFF hand (-1 = never). */
    private long lastOffHandAttack = -1;

    public Wielder(Player player)
    {
        this.player = player;
    }

    public long getTime()
    {
        return time;
    }

    public void setTime(long time)
    {
        this.time = time;
    }

    public void setTimeNow()
    {
        this.time = System.currentTimeMillis();
    }

    public boolean isUsingLeftWeapon()
    {
        return usingLeftWeapon;
    }

    public void setUsingLeftWeapon(boolean usingLeftWeapon)
    {
        this.usingLeftWeapon = usingLeftWeapon;
    }

    @Nullable
    public Integer getDelay()
    {
        return delay;
    }

    public void setDelay(@Nullable Integer delay)
    {
        this.delay = delay;
    }

    /**
     * Remembers that the player just hit something with the main hand, so that the off-hand combo
     * can be forced to wait a little instead of being dealt on the very same frame.
     */
    public void markMainHandAttack()
    {
        this.lastMainHandAttack = System.currentTimeMillis();
    }

    public void markOffHandAttack()
    {
        this.lastOffHandAttack = System.currentTimeMillis();
    }

    /** Milliseconds since the last main hand hit, or -1 when the player never hit anything. */
    public long millisSinceMainHandAttack()
    {
        return lastMainHandAttack < 0 ? -1 : System.currentTimeMillis() - lastMainHandAttack;
    }

    /** Milliseconds since the last off hand hit, or -1 when the player never hit anything. */
    public long millisSinceOffHandAttack()
    {
        return lastOffHandAttack < 0 ? -1 : System.currentTimeMillis() - lastOffHandAttack;
    }

    public int getCooldownTotal()
    {
        return cooldownTotal;
    }

    public void setCooldownTotal(int cooldownTotal)
    {
        this.cooldownTotal = cooldownTotal <= 0 ? 12 : cooldownTotal;
    }

    public Player getPlayer()
    {
        return player;
    }

    /**
     * Answers whether the player is holding the right button down, looking only at the
     * {@link org.bukkit.event.player.PlayerInteractEvent} (main hand) stream.
     *
     * <p>The two kinds of interaction are tracked separately on purpose. The 1.2x implementation
     * compared the entity interaction with the timestamp of the *previous generic* interaction,
     * with an inverted comparison: the off-hand attack only went through when a
     * {@code PlayerInteractEvent} had been fired less than 120&nbsp;ms before. On Minecraft 26.2 the
     * two events are delivered in the opposite order (the entity one first), so that test was
     * always true, every attack was skipped and only the swing animation - which comes from
     * {@code PlayerInteractEvent} - was played.
     *
     * <p>The check is now self contained: each stream keeps its own timestamps, and a button is
     * considered "held" only after two consecutive events closer than
     * {@link #HOLD_THRESHOLD_MS}, which never happens on a plain click, whatever the event order.
     */
    public boolean isHoldingInteract()
    {
        if (!DualWielding.DENY_LONGPRESS_RIGHTCLICK)
            return false;

        return isHeld(lastInteractTime, lastInteractGap);
    }

    public void markInteract()
    {
        long now = System.currentTimeMillis();
        this.lastInteractGap = now - lastInteractTime;
        this.lastInteractTime = now;
        setTimeNow();
    }

    /**
     * Answers whether the player is holding the right button down, looking only at the entity
     * interaction events ({@link org.bukkit.event.player.PlayerInteractEntityEvent}).
     *
     * @see #isHoldingInteract() why the two streams are kept apart.
     */
    public boolean isHoldingRightClick()
    {
        if (!DualWielding.DENY_LONGPRESS_RIGHTCLICK)
            return false;

        return isHeld(lastEntityInteractTime, lastEntityInteractGap);
    }

    private boolean isHeld(long last, long lastGap)
    {
        if (last == 0)
            return false;

        long now = System.currentTimeMillis();
        return now - last < HOLD_THRESHOLD_MS && lastGap < HOLD_THRESHOLD_MS;
    }

    public void markEntityInteract()
    {
        long now = System.currentTimeMillis();
        this.lastEntityInteractGap = now - lastEntityInteractTime;
        this.lastEntityInteractTime = now;
        setTimeNow();
    }

    public void instamine(Block block, ItemStack weapon)
    {
        if (block == null || player.getGameMode() == GameMode.ADVENTURE)
            return;

        if (player.getInventory().getItemInOffHand().getType() == Material.AIR)
        {
            if (DualWielding.BREAK_PLANTS_BARE_HAND)
                offhandAnimation();
            return;
        }

        if (!Utils.canInstaMine(block))
            return;
        if (!Utils.canBreak(block, player))
            return;

        if (!Events.call(new PlayerOffhandPlantBreakEvent(player, weapon, block)))
            return;

        Location loc = block.getLocation();
        loc.setX(loc.getX() + 0.5f);
        loc.setY(loc.getY() + 0.5f);
        loc.setZ(loc.getZ() + 0.5f);
        block.getWorld().spawnParticle(Particle.BLOCK, loc.getX(), loc.getY(), loc.getZ(), 30, 0.2f, 0.2f, 0.2f, block.getType().createBlockData());
        block.getWorld().playSound(loc, Sound.BLOCK_GRASS_BREAK, 1, 1);
        for (ItemStack drop : block.getDrops())
            block.getWorld().dropItemNaturally(block.getLocation(), drop);
        block.getLocation().getBlock().setType(Material.AIR);
    }

    /**
     * Plays the off-hand swing animation.
     *
     * <p>On Minecraft 26.x the animation packet is an immutable record that ProtocolLib cannot
     * allocate by itself, so the whole logic (packet allocation + fallback on the Paper API) lives
     * in {@link OffhandAnimation}.
     */
    public void offhandAnimation()
    {
        OffhandAnimation.play(player);
    }
}
