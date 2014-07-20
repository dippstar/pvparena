package net.slipcor.pvparena.goals;

import net.slipcor.pvparena.PVPArena;
import net.slipcor.pvparena.arena.ArenaClass;
import net.slipcor.pvparena.arena.ArenaPlayer;
import net.slipcor.pvparena.arena.ArenaPlayer.Status;
import net.slipcor.pvparena.arena.ArenaTeam;
import net.slipcor.pvparena.classes.PACheck;
import net.slipcor.pvparena.core.Config.CFG;
import net.slipcor.pvparena.core.Debug;
import net.slipcor.pvparena.core.Language;
import net.slipcor.pvparena.core.Language.MSG;
import net.slipcor.pvparena.events.PAGoalEvent;
import net.slipcor.pvparena.loadables.ArenaGoal;
import net.slipcor.pvparena.loadables.ArenaModuleManager;
import net.slipcor.pvparena.managers.ArenaManager;
import net.slipcor.pvparena.managers.InventoryManager;
import net.slipcor.pvparena.runnables.EndRunnable;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/**
 * <pre>
 * Arena Goal class "PlayerDeathMatch"
 * </pre>
 * <p/>
 * The first Arena Goal. Players have lives. When every life is lost, the player
 * is teleported to the spectator spawn to watch the rest of the fight.
 *
 * @author slipcor
 */

public class GoalPlayerDeathMatch extends ArenaGoal {
    public GoalPlayerDeathMatch() {
        super("PlayerDeathMatch");
        debug = new Debug(101);
    }

    private EndRunnable endRunner;

    @Override
    public String version() {
        return PVPArena.instance.getDescription().getVersion();
    }

    private static final int PRIORITY = 3;

    @Override
    public boolean allowsJoinInBattle() {
        return arena.getArenaConfig().getBoolean(CFG.PERMS_JOININBATTLE);
    }

    @Override
    public PACheck checkEnd(final PACheck res) {
        if (res.getPriority() > PRIORITY) {
            return res;
        }

        final int count = getLifeMap().size();

        if (count <= 1) {
            res.setPriority(this, PRIORITY); // yep. only one player left. go!
        }
        if (count == 0) {
            res.setError(this, MSG.ERROR_NOPLAYERFOUND.toString());
        }

        return res;
    }

    @Override
    public String checkForMissingSpawns(final Set<String> list) {
        if (!arena.isFreeForAll()) {
            return null; // teams are handled somewhere else
        }

        return checkForMissingSpawn(list);
    }

    @Override
    public PACheck checkJoin(final CommandSender sender, final PACheck res, final String[] args) {
        if (res.getPriority() >= PRIORITY) {
            return res;
        }

        final int maxPlayers = arena.getArenaConfig().getInt(CFG.READY_MAXPLAYERS);
        final int maxTeamPlayers = arena.getArenaConfig().getInt(
                CFG.READY_MAXTEAMPLAYERS);

        if (maxPlayers > 0 && arena.getFighters().size() >= maxPlayers) {
            res.setError(this, Language.parse(arena, MSG.ERROR_JOIN_ARENA_FULL));
            return res;
        }

        if (args == null || args.length < 1) {
            return res;
        }

        if (!arena.isFreeForAll()) {
            final ArenaTeam team = arena.getTeam(args[0]);

            if (team != null && maxTeamPlayers > 0
                    && team.getTeamMembers().size() >= maxTeamPlayers) {
                res.setError(this, Language.parse(arena, MSG.ERROR_JOIN_TEAM_FULL));
                return res;
            }
        }

        res.setPriority(this, PRIORITY);
        return res;
    }

    @Override
    public PACheck checkPlayerDeath(final PACheck res, final Player player) {
        if (res.getPriority() <= PRIORITY && player.getKiller() != null
                && arena.hasPlayer(player.getKiller())) {
            res.setPriority(this, PRIORITY);
        }
        return res;
    }

    @Override
    public void commitEnd(final boolean force) {
        if (endRunner != null) {
            return;
        }
        if (arena.realEndRunner != null) {
            arena.getDebugger().i("[PDM] already ending");
            return;
        }
        final PAGoalEvent gEvent = new PAGoalEvent(arena, this, "");
        Bukkit.getPluginManager().callEvent(gEvent);
        for (final ArenaTeam team : arena.getTeams()) {
            for (final ArenaPlayer ap : team.getTeamMembers()) {
                if (ap.getStatus() != Status.FIGHT) {
                    continue;
                }
                ArenaModuleManager.announce(arena,
                        Language.parse(arena, MSG.PLAYER_HAS_WON, ap.getName()),
                        "END");
                ArenaModuleManager.announce(arena,
                        Language.parse(arena, MSG.PLAYER_HAS_WON, ap.getName()),
                        "WINNER");

                arena.broadcast(Language.parse(arena, MSG.PLAYER_HAS_WON, ap.getName()));
            }
            if (ArenaModuleManager.commitEnd(arena, team)) {
                return;
            }
        }
        endRunner = new EndRunnable(arena, arena.getArenaConfig().getInt(
                CFG.TIME_ENDCOUNTDOWN));
    }

    @Override
    public void commitPlayerDeath(final Player player, final boolean doesRespawn,
                                  final String error, final PlayerDeathEvent event) {

        if (player.getKiller() == null
                || !getLifeMap().containsKey(player.getKiller().getName())
                || player.getPlayer().equals(player.getPlayer().getKiller())) {
            if (arena.getArenaConfig().getBoolean(CFG.USES_DEATHMESSAGES)) {
                final ArenaTeam respawnTeam = ArenaPlayer.parsePlayer(player.getName())
                        .getArenaTeam();
                arena.broadcast(Language.parse(arena,
                        MSG.FIGHT_KILLED_BY,
                        respawnTeam.colorizePlayer(player) + ChatColor.YELLOW,
                        arena.parseDeathCause(player, event.getEntity()
                                .getLastDamageCause().getCause(), player)));
            }

            final List<ItemStack> returned;

            if (arena.isCustomClassAlive()
                    || arena.getArenaConfig().getBoolean(
                    CFG.PLAYER_DROPSINVENTORY)) {
                returned = InventoryManager.drop(player);
                event.getDrops().clear();
            } else {
                returned = new ArrayList<ItemStack>();
                returned.addAll(event.getDrops());
            }

            PACheck.handleRespawn(arena, ArenaPlayer.parsePlayer(player.getName()), returned);

            return;
        }
        final Player killer = player.getKiller();

        int iLives = getLifeMap().get(killer.getName());
        arena.getDebugger().i("kills to go: " + iLives, killer);
        if (iLives <= 1) {
            // player has won!
            final PAGoalEvent gEvent = new PAGoalEvent(arena, this, "trigger:" + killer.getName(),
                    "playerKill:" + killer.getName() + ":" + player.getName(), "playerDeath:" + player.getName());
            Bukkit.getPluginManager().callEvent(gEvent);
            final Set<ArenaPlayer> plrs = new HashSet<ArenaPlayer>();
            for (final ArenaPlayer ap : arena.getFighters()) {
                if (ap.getName().equals(killer.getName())) {
                    continue;
                }
                plrs.add(ap);
            }
            for (final ArenaPlayer ap : plrs) {
                getLifeMap().remove(ap.getName());
//				arena.getDebugger().i("faking player death", ap.get());
//				arena.removePlayer(ap.get(), CFG.TP_LOSE.toString(), true,
//						false);

                ap.setStatus(Status.LOST);
                ap.addLosses();

//				PlayerState.fullReset(arena, ap.get());

                if (ArenaManager.checkAndCommit(arena, false)) {
                    arena.unKillPlayer(player, event.getEntity()
                            .getLastDamageCause().getCause(), player.getKiller());
                    return;
                }
            }

            arena.unKillPlayer(player, event.getEntity()
                    .getLastDamageCause().getCause(), player.getKiller());

            PACheck.handleEnd(arena, false);
        } else {
            final PAGoalEvent gEvent = new PAGoalEvent(arena, this, "playerKill:" + killer.getName() + ":" + player.getName(), "playerDeath:" + player.getName());
            Bukkit.getPluginManager().callEvent(gEvent);
            iLives--;
            getLifeMap().put(killer.getName(), iLives);

            final ArenaTeam respawnTeam = ArenaPlayer.parsePlayer(player.getName())
                    .getArenaTeam();
            if (arena.getArenaConfig().getBoolean(CFG.USES_DEATHMESSAGES)) {
                if (arena.getArenaConfig().getBoolean(CFG.GENERAL_SHOWREMAININGLIVES)) {
                    arena.broadcast(Language.parse(arena,
                            MSG.FIGHT_KILLED_BY_REMAINING_FRAGS,
                            respawnTeam.colorizePlayer(player) + ChatColor.YELLOW,
                            arena.parseDeathCause(player, event.getEntity()
                                    .getLastDamageCause().getCause(), killer),
                            String.valueOf(iLives)));
                } else {
                    arena.broadcast(Language.parse(arena,
                            MSG.FIGHT_KILLED_BY,
                            respawnTeam.colorizePlayer(player) + ChatColor.YELLOW,
                            arena.parseDeathCause(player, event.getEntity()
                                    .getLastDamageCause().getCause(), killer)));
                }

            }

            final List<ItemStack> returned;

            if (arena.isCustomClassAlive()
                    || arena.getArenaConfig().getBoolean(
                    CFG.PLAYER_DROPSINVENTORY)) {
                returned = InventoryManager.drop(player);
                event.getDrops().clear();
            } else {
                returned = new ArrayList<ItemStack>();
                returned.addAll(event.getDrops());
            }

            PACheck.handleRespawn(arena, ArenaPlayer.parsePlayer(player.getName()), returned);

        }
    }

    @Override
    public void displayInfo(final CommandSender sender) {
        sender.sendMessage("lives: "
                + arena.getArenaConfig().getInt(CFG.GOAL_PDM_LIVES));
    }

    @Override
    public PACheck getLives(final PACheck res, final ArenaPlayer aPlayer) {
        if (res.getPriority() <= PRIORITY + 1000) {
            res.setError(
                    this,
                    String.valueOf(arena.getArenaConfig()
                            .getInt(CFG.GOAL_PDM_LIVES) - (getLifeMap()
                            .containsKey(aPlayer.getName()) ? getLifeMap().get(aPlayer
                            .getName()) : 0)));
        }
        return res;
    }

    @Override
    public boolean hasSpawn(final String string) {

        if (arena.getArenaConfig().getBoolean(CFG.GENERAL_CLASSSPAWN)) {
            for (final ArenaClass aClass : arena.getClasses()) {
                if (string.toLowerCase().startsWith(
                        aClass.getName().toLowerCase() + "spawn")) {
                    return true;
                }
            }
        }
        return arena.isFreeForAll() && string.toLowerCase()
                .startsWith("spawn");
    }

    @Override
    public void initate(final Player player) {
        updateLives(player, arena.getArenaConfig().getInt(CFG.GOAL_PDM_LIVES));
    }

    @Override
    public boolean isInternal() {
        return true;
    }

    @Override
    public void lateJoin(final Player player) {
        initate(player);
    }

    @Override
    public void parseLeave(final Player player) {
        if (player == null) {
            PVPArena.instance.getLogger().warning(
                    getName() + ": player NULL");
            return;
        }
        if (getLifeMap().containsKey(player.getName())) {
            getLifeMap().remove(player.getName());
        }
    }

    @Override
    public void parseStart() {
        for (final ArenaTeam team : arena.getTeams()) {
            for (final ArenaPlayer ap : team.getTeamMembers()) {
                updateLives(ap.get(), arena.getArenaConfig().getInt(CFG.GOAL_PDM_LIVES));
            }
        }
    }

    @Override
    public void reset(final boolean force) {
        endRunner = null;
        getLifeMap().clear();
    }

    @Override
    public Map<String, Double> timedEnd(final Map<String, Double> scores) {
        double score;

        for (final ArenaPlayer ap : arena.getFighters()) {
            score = arena.getArenaConfig().getInt(CFG.GOAL_PDM_LIVES)
                    - (getLifeMap().containsKey(ap.getName()) ? getLifeMap()
                    .get(ap.getName()) : 0);
            if (scores.containsKey(ap.getName())) {
                scores.put(ap.getName(), scores.get(ap.getName()) + score);
            } else {
                scores.put(ap.getName(), score);
            }
        }

        return scores;
    }

    @Override
    public void unload(final Player player) {
        getLifeMap().remove(player.getName());
        if (allowsJoinInBattle()) {
            arena.hasNotPlayed(ArenaPlayer.parsePlayer(player.getName()));
        }
    }
}
