package cz.xray;

import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.TabExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class XRayPlugin extends JavaPlugin implements Listener, TabExecutor {

    record OrePos(int x, int y, int z, Material type) {}

    record ChunkRef(World world, int cx, int cz) {}

    private static final class Session {
        World world;
        final Set<String> filter = new LinkedHashSet<>(); // empty = all ores
        final Map<OrePos, BlockDisplay> shown = new HashMap<>();
    }

    private static final List<String> GROUPS = List.of(
            "coal", "iron", "copper", "gold", "redstone", "lapis",
            "diamond", "emerald", "quartz", "debris");

    private static final Set<Material> ORES = EnumSet.noneOf(Material.class);
    private static final Map<Material, String> GROUP_OF = new EnumMap<>(Material.class);

    static {
        for (Material m : Material.values()) {
            if (m.isLegacy()) continue;
            String n = m.name();
            if (n.endsWith("_ORE") || m == Material.ANCIENT_DEBRIS) {
                ORES.add(m);
                GROUP_OF.put(m, groupOf(m));
            }
        }
    }

    private static String groupOf(Material m) {
        if (m == Material.ANCIENT_DEBRIS) return "debris";
        String n = m.name().toLowerCase();
        for (String g : GROUPS) {
            if (n.contains(g)) return g;
        }
        return "other";
    }

    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, Map<Long, List<OrePos>>> cache = new ConcurrentHashMap<>();
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    private final ArrayDeque<ChunkRef> queue = new ArrayDeque<>();

    private int radius;
    private int maxEntities;
    private int chunksPerTick;
    private boolean nightVision;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        radius = getConfig().getInt("radius", 200);
        maxEntities = getConfig().getInt("max-entities", 2000);
        chunksPerTick = getConfig().getInt("chunks-per-tick", 4);
        nightVision = getConfig().getBoolean("night-vision", true);
        int interval = getConfig().getInt("update-interval-ticks", 40);

        getServer().getPluginManager().registerEvents(this, this);
        getCommand("xray").setExecutor(this);

        Bukkit.getScheduler().runTaskTimer(this, this::processQueue, 1L, 1L);
        Bukkit.getScheduler().runTaskTimer(this, this::updateAll, 20L, interval);
    }

    @Override
    public void onDisable() {
        for (Map.Entry<UUID, Session> e : sessions.entrySet()) {
            clear(e.getValue());
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null) removeNightVision(p);
        }
        sessions.clear();
    }

    // ---------- command ----------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }
        if (!p.hasPermission("xray.use")) {
            p.sendMessage("§cYou don't have permission.");
            return true;
        }

        UUID id = p.getUniqueId();
        Session existing = sessions.get(id);

        // /xray  -> toggle (all ores)
        if (args.length == 0) {
            if (existing != null) {
                disable(id, p);
            } else {
                enable(p, new Session(), Set.of());
            }
            return true;
        }

        String first = args[0].toLowerCase();

        if (first.equals("off")) {
            if (existing != null) disable(id, p);
            else p.sendMessage("§cXRay is not enabled.");
            return true;
        }

        if (first.equals("list")) {
            p.sendMessage("§eAvailable ores: §f" + String.join(", ", GROUPS));
            p.sendMessage("§7Usage: /xray [ore ...] | /xray all | /xray off");
            return true;
        }

        // /xray all | /xray <ore> [ore ...]
        Set<String> wanted = new LinkedHashSet<>();
        if (!first.equals("all")) {
            for (String arg : args) {
                String g = normalize(arg);
                if (g == null) {
                    p.sendMessage("§cUnknown ore: §f" + arg);
                    p.sendMessage("§eAvailable ores: §f" + String.join(", ", GROUPS));
                    return true;
                }
                wanted.add(g);
            }
        }
        enable(p, existing != null ? existing : new Session(), wanted);
        return true;
    }

    private void enable(Player p, Session s, Set<String> filter) {
        s.filter.clear();
        s.filter.addAll(filter);
        sessions.put(p.getUniqueId(), s);
        String what = filter.isEmpty() ? "all ores" : String.join(", ", filter);
        p.sendMessage("§aXRay enabled: §f" + what + " §7(radius " + radius + " blocks, updates every 2 s)");
        refresh(p, s);
    }

    private void disable(UUID id, Player p) {
        Session s = sessions.remove(id);
        if (s != null) clear(s);
        removeNightVision(p);
        p.sendMessage("§cXRay disabled.");
    }

    /** Maps user input (diamond, diamonds, ancient_debris, ...) to an ore group, or null. */
    private static String normalize(String input) {
        String a = input.toLowerCase().replace("-", "_");
        if (a.equals("ancient_debris") || a.equals("netherite")) return "debris";
        if (GROUPS.contains(a)) return a;
        if (a.endsWith("s") && GROUPS.contains(a.substring(0, a.length() - 1))) {
            return a.substring(0, a.length() - 1);
        }
        return null;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (!sender.hasPermission("xray.use")) return List.of();
        String current = args[args.length - 1].toLowerCase();
        List<String> options = new ArrayList<>(GROUPS);
        if (args.length == 1) {
            options.add("all");
            options.add("off");
            options.add("list");
        } else {
            for (int i = 0; i < args.length - 1; i++) options.remove(args[i].toLowerCase());
        }
        options.removeIf(o -> !o.startsWith(current));
        return options;
    }

    // ---------- night vision ----------

    private void giveNightVision(Player p) {
        if (!nightVision) return;
        PotionEffect current = p.getPotionEffect(PotionEffectType.NIGHT_VISION);
        if (current != null && current.getDuration() == PotionEffect.INFINITE_DURATION) return;
        p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION,
                PotionEffect.INFINITE_DURATION, 0, false, false, false));
    }

    private void removeNightVision(Player p) {
        if (!nightVision) return;
        p.removePotionEffect(PotionEffectType.NIGHT_VISION);
    }

    // ---------- updating ----------

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
        giveNightVision(p);
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
                    if (!s.filter.isEmpty() && !s.filter.contains(GROUP_OF.get(o.type()))) continue;
                    if (dist2(o, px, py, pz) <= r2) candidates.add(o);
                }
            }
        }
        candidates.sort(Comparator.comparingLong(o -> dist2(o, px, py, pz)));

        Set<OrePos> keep = new HashSet<>();
        for (OrePos o : candidates) {
            if (keep.size() >= maxEntities) break;
            if (!w.isChunkLoaded(o.x() >> 4, o.z() >> 4)) continue;
            // ore is gone (mined / changed) -> rescan chunk
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
            bd.setVisibleByDefault(false); // only the admin can see it
            bd.setPersistent(false);
            bd.setBlock(o.type().createBlockData());
            bd.setGlowing(true);
            bd.setGlowColorOverride(colorFor(o.type()));
            bd.setViewRange(4f); // multiplier of 64 blocks
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

    // ---------- chunk scanning ----------

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

    // ---------- colors ----------

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

    // ---------- events ----------

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent e) {
        Map<Long, List<OrePos>> wc = cache.get(e.getWorld().getUID());
        if (wc != null) wc.remove(chunkKey(e.getChunk().getX(), e.getChunk().getZ()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Session s = sessions.remove(e.getPlayer().getUniqueId());
        if (s != null) {
            clear(s);
            removeNightVision(e.getPlayer());
        }
    }
}
