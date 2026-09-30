package cz.xray;

import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class XRayPlugin extends JavaPlugin implements Listener, CommandExecutor {

    record OrePos(int x, int y, int z, Material type) {}

    record ChunkRef(World world, int cx, int cz) {}

    private static final class Session {
        World world;
        final Map<OrePos, BlockDisplay> shown = new HashMap<>();
    }

    private static final Set<Material> ORES = EnumSet.noneOf(Material.class);

    static {
        for (Material m : Material.values()) {
            if (m.isLegacy()) continue;
            String n = m.name();
            if (n.endsWith("_ORE") || m == Material.ANCIENT_DEBRIS) ORES.add(m);
        }
    }

    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, Map<Long, List<OrePos>>> cache = new ConcurrentHashMap<>();
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    private final ArrayDeque<ChunkRef> queue = new ArrayDeque<>();

    private int radius;
    private int maxEntities;
    private int chunksPerTick;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        radius = getConfig().getInt("radius", 200);
        maxEntities = getConfig().getInt("max-entities", 2000);
        chunksPerTick = getConfig().getInt("chunks-per-tick", 4);
        int interval = getConfig().getInt("update-interval-ticks", 40);

        getServer().getPluginManager().registerEvents(this, this);
        getCommand("xray").setExecutor(this);

        Bukkit.getScheduler().runTaskTimer(this, this::processQueue, 1L, 1L);
        Bukkit.getScheduler().runTaskTimer(this, this::updateAll, 20L, interval);
    }

    @Override
    public void onDisable() {
        for (Session s : sessions.values()) clear(s);
        sessions.clear();
    }

    // ---------- příkaz ----------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Tento příkaz může použít jen hráč.");
            return true;
        }
        if (!p.hasPermission("xray.use")) {
            p.sendMessage("§cNemáš oprávnění.");
            return true;
        }
        Session existing = sessions.remove(p.getUniqueId());
        if (existing != null) {
            clear(existing);
            p.sendMessage("§cXRay vypnut.");
        } else {
            sessions.put(p.getUniqueId(), new Session());
            p.sendMessage("§aXRay zapnut §7(dosah " + radius + " bloků, aktualizace každé 2 s).");
            refresh(p, sessions.get(p.getUniqueId()));
        }
        return true;
    }

    // ---------- aktualizace ----------

    private void updateAll() {
        Iterator<Map.Entry<UUID, Session>> it = sessions.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null || !p.isOnline()) {
                clear(e.getValue());
                it.remove();
                continue;
            }
            refresh(p, e.getValue());
        }
    }

    private void refresh(Player p, Session s) {
        World w = p.getWorld();
        if (s.world != w) {
            clear(s);
            s.world = w;
        }
        Location pl = p.getLocation();
        final int px = pl.getBlockX(), py = pl.getBlockY(), pz = pl.getBlockZ();
        final long r2 = (long) radius * radius;

        Map<Long, List<OrePos>> wc = cache.computeIfAbsent(w.getUID(), k -> new ConcurrentHashMap<>());
        List<OrePos> candidates = new ArrayList<>();

        int minCx = (px - radius) >> 4, maxCx = (px + radius) >> 4;
        int minCz = (pz - radius) >> 4, maxCz = (pz + radius) >> 4;
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                long key = chunkKey(cx, cz);
                List<OrePos> list = wc.get(key);
                if (list == null) {
                    enqueue(w, cx, cz);
                    continue;
                }
                for (OrePos o : list) {
                    if (dist2(o, px, py, pz) <= r2) candidates.add(o);
                }
            }
        }
        candidates.sort(Comparator.comparingLong(o -> dist2(o, px, py, pz)));

        Set<OrePos> keep = new HashSet<>();
        for (OrePos o : candidates) {
            if (keep.size() >= maxEntities) break;
            if (!w.isChunkLoaded(o.x() >> 4, o.z() >> 4)) continue;
            // ruda už tam není (vytěžená / změněná) -> přeskenovat chunk
            if (w.getBlockAt(o.x(), o.y(), o.z()).getType() != o.type()) {
                wc.remove(chunkKey(o.x() >> 4, o.z() >> 4));
                continue;
            }
            keep.add(o);
            BlockDisplay d = s.shown.get(o);
            if (d == null || !d.isValid()) {
                s.shown.put(o, spawnMarker(p, w, o));
            }
        }

        s.shown.entrySet().removeIf(en -> {
            if (keep.contains(en.getKey())) return false;
            en.getValue().remove();
            return true;
        });
    }

    private static long dist2(OrePos o, int px, int py, int pz) {
        long dx = o.x() - px, dy = o.y() - py, dz = o.z() - pz;
        return dx * dx + dy * dy + dz * dz;
    }

    private BlockDisplay spawnMarker(Player viewer, World w, OrePos o) {
        Location loc = new Location(w, o.x(), o.y(), o.z());
        BlockDisplay d = w.spawn(loc, BlockDisplay.class, bd -> {
            bd.setVisibleByDefault(false); // vidí ho jen admin
            bd.setPersistent(false);
            bd.setBlock(o.type().createBlockData());
            bd.setGlowing(true);
            bd.setGlowColorOverride(colorFor(o.type()));
            bd.setViewRange(4f); // násobek 64 bloků
            bd.setTransformation(new Transformation(
                    new Vector3f(0.2f, 0.2f, 0.2f), new AxisAngle4f(),
                    new Vector3f(0.6f, 0.6f, 0.6f), new AxisAngle4f()));
        });
        viewer.showEntity(this, d);
        return d;
    }

    private void clear(Session s) {
        for (BlockDisplay d : s.shown.values()) d.remove();
        s.shown.clear();
    }

    // ---------- skenování chunků ----------

    private static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    private void enqueue(World w, int cx, int cz) {
        if (!w.isChunkLoaded(cx, cz)) return;
        String pk = w.getUID() + ":" + chunkKey(cx, cz);
        if (pending.add(pk)) queue.add(new ChunkRef(w, cx, cz));
    }

    private void processQueue() {
        for (int i = 0; i < chunksPerTick; i++) {
            ChunkRef c = queue.poll();
            if (c == null) return;
            World w = c.world();
            long key = chunkKey(c.cx(), c.cz());
            String pk = w.getUID() + ":" + key;
            if (!w.isChunkLoaded(c.cx(), c.cz())) {
                pending.remove(pk);
                continue;
            }
            ChunkSnapshot snap = w.getChunkAt(c.cx(), c.cz()).getChunkSnapshot(false, false, false);
            int minY = w.getMinHeight(), maxY = w.getMaxHeight();
            UUID uid = w.getUID();
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                List<OrePos> res = scan(snap, minY, maxY);
                cache.computeIfAbsent(uid, k -> new ConcurrentHashMap<>()).put(key, res);
                pending.remove(pk);
            });
        }
    }

    private static List<OrePos> scan(ChunkSnapshot snap, int minY, int maxY) {
        List<OrePos> out = new ArrayList<>();
        int sections = (maxY - minY) >> 4;
        for (int sec = 0; sec < sections; sec++) {
            if (snap.isSectionEmpty(sec)) continue;
            int y0 = minY + sec * 16;
            for (int y = y0; y < y0 + 16; y++) {
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        Material m = snap.getBlockType(x, y, z);
                        if (ORES.contains(m)) {
                            out.add(new OrePos(snap.getX() * 16 + x, y, snap.getZ() * 16 + z, m));
                        }
                    }
                }
            }
        }
        return out;
    }

    // ---------- barvy ----------

    private static Color colorFor(Material m) {
        String n = m.name();
        if (n.contains("DIAMOND")) return Color.AQUA;
        if (n.contains("EMERALD")) return Color.LIME;
        if (n.contains("GOLD")) return Color.YELLOW;
        if (n.contains("IRON")) return Color.fromRGB(216, 175, 147);
        if (n.contains("COAL")) return Color.fromRGB(60, 60, 60);
        if (n.contains("COPPER")) return Color.ORANGE;
        if (n.contains("REDSTONE")) return Color.RED;
        if (n.contains("LAPIS")) return Color.BLUE;
        if (n.contains("QUARTZ")) return Color.WHITE;
        if (m == Material.ANCIENT_DEBRIS) return Color.fromRGB(120, 70, 40);
        return Color.PURPLE;
    }

    // ---------- události ----------

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent e) {
        Map<Long, List<OrePos>> wc = cache.get(e.getWorld().getUID());
        if (wc != null) wc.remove(chunkKey(e.getChunk().getX(), e.getChunk().getZ()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Session s = sessions.remove(e.getPlayer().getUniqueId());
        if (s != null) clear(s);
    }
}
