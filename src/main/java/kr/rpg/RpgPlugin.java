package kr.rpg;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.*;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.*;

public class RpgPlugin extends JavaPlugin implements Listener {

    // ───────── 종족 / 직업 정의 ─────────
    enum Race {
        HUMAN("사람", "능력 없음"),
        MERFOLK("어인", "물속: 채굴 200%·산소 무한·힘2배·저항 / 지상: 물고기 (체력·이속 매우 낮음)"),
        BIRD("새", "겉날개 지급(귀속저주)·낙하피해 없음 / 체력 절반"),
        PERI("페리", "이동속도·점프력 20% 증가 / 채팅 끝에 meow가 붙음"),
        VAMPIRE("흡혈귀", "타격 시 0.5칸 회복·밤 이속 30% / 낮엔 불탐·익힌 고기 불가·불 데미지 매우 약함"),
        BLIND("장님", "힘 2배·저항 2배 / 실명 효과"),
        GIANT("거인", "힘 2배·저항 2배·이속 1.5배·신체 2배"),
        DWARF("소인", "힘 0.5배·낙하피해 2배·이속 1.5배·신체 0.5배");
        final String name, desc;
        Race(String n, String d) { name = n; desc = d; }
    }

    enum Job {
        NONE("없음", ""),
        SWORDSMAN("검사", "검 데미지 +20% / 타격 1초 뒤 같은 데미지 한 번 더"),
        ARCHER("궁수", "활 데미지 +20% / 화살이 한 번에 3발"),
        BRAWLER("길거리 싸움꾼", "맨손 데미지 +20% / 넉백+같은 데미지를 3초에 걸쳐 2번"),
        MUSICIAN("음악가", "머리에 주크박스 / 주기마다 3블록 파동(방어 무시 0.5칸) / 웅크리고 우클릭: 5초 공격 +20%");
        final String name, desc;
        Job(String n, String d) { name = n; desc = d; }
    }

    // 직업별 티어 보너스 (인덱스 0 = 기본, 1~3 = t1~t3)
    static final double[] SWORD = {0.20, 0.25, 0.30, 0.40};
    static final double[] BOW   = {0.20, 0.25, 0.30, 0.40};
    static final double[] FIST  = {0.20, 0.40, 0.60, 1.00};
    static final int[] MUSIC_INTERVAL = {5, 3, 2, 1}; // 초

    NamespacedKey K_RACE, K_JOB, K_TIER, K_ITEM;
    final Map<UUID, Long> buffUntil = new HashMap<>();
    final Map<UUID, Long> buffCooldown = new HashMap<>();
    boolean reentry = false;
    long counter = 0;

    @Override
    public void onEnable() {
        K_RACE = new NamespacedKey(this, "race");
        K_JOB = new NamespacedKey(this, "job");
        K_TIER = new NamespacedKey(this, "tier");
        K_ITEM = new NamespacedKey(this, "item");
        getServer().getPluginManager().registerEvents(this, this);
        registerRecipes();
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 20L, 20L);
    }

    // ───────── 데이터 접근 ─────────
    Race race(Player p) {
        String s = p.getPersistentDataContainer().get(K_RACE, PersistentDataType.STRING);
        if (s == null) return null;
        try { return Race.valueOf(s); } catch (Exception e) { return null; }
    }
    Job job(Player p) {
        String s = p.getPersistentDataContainer().get(K_JOB, PersistentDataType.STRING);
        if (s == null) return null;
        try { return Job.valueOf(s); } catch (Exception e) { return null; }
    }
    int tier(Player p) {
        return p.getPersistentDataContainer().getOrDefault(K_TIER, PersistentDataType.INTEGER, 0);
    }

    // ───────── 아이템 / 레시피 ─────────
    ItemStack special(String type) {
        Material m; String name; NamedTextColor c;
        switch (type) {
            case "race_ticket" -> { m = Material.PAPER; name = "종족 선택권"; c = NamedTextColor.AQUA; }
            case "job_ticket" -> { m = Material.PAPER; name = "직업 선택권"; c = NamedTextColor.GOLD; }
            default -> { m = Material.FIREWORK_STAR; name = "전직의 별"; c = NamedTextColor.LIGHT_PURPLE; }
        }
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text(name, c));
        meta.getPersistentDataContainer().set(K_ITEM, PersistentDataType.STRING, type);
        it.setItemMeta(meta);
        return it;
    }
    String itemType(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(K_ITEM, PersistentDataType.STRING);
    }

    void registerRecipes() {
        ShapelessRecipe r1 = new ShapelessRecipe(new NamespacedKey(this, "race_ticket"), special("race_ticket"));
        r1.addIngredient(Material.PAPER);
        r1.addIngredient(Material.NETHERITE_INGOT);
        Bukkit.addRecipe(r1);

        ShapelessRecipe r2 = new ShapelessRecipe(new NamespacedKey(this, "job_ticket"), special("job_ticket"));
        r2.addIngredient(Material.PAPER);
        r2.addIngredient(Material.NETHER_STAR);
        Bukkit.addRecipe(r2);

        ShapelessRecipe r3 = new ShapelessRecipe(new NamespacedKey(this, "class_star"), special("class_star"));
        for (Material m : new Material[]{Material.EMERALD_BLOCK, Material.DIAMOND_BLOCK, Material.GOLD_BLOCK,
                Material.REDSTONE_BLOCK, Material.IRON_BLOCK, Material.COPPER_BLOCK, Material.PAPER, Material.COBBLESTONE})
            r3.addIngredient(m);
        Bukkit.addRecipe(r3);
    }

    // ───────── 채팅 UI ─────────
    void sendMenu(Player p, boolean isRace) {
        p.sendMessage(Component.text("━━━━━ " + (isRace ? "종족" : "직업") + " 선택 ━━━━━", NamedTextColor.YELLOW));
        if (isRace) {
            for (Race r : Race.values()) p.sendMessage(line(r.name, r.desc, "/rpg pick race " + r.name()));
        } else {
            for (Job j : Job.values()) {
                if (j == Job.NONE) continue;
                p.sendMessage(line(j.name, j.desc, "/rpg pick job " + j.name()));
            }
        }
        p.sendMessage(Component.text("이름을 클릭하면 선택됩니다.", NamedTextColor.GRAY));
    }
    Component line(String name, String desc, String cmd) {
        return Component.text("▶ [" + name + "]", NamedTextColor.GREEN)
                .hoverEvent(HoverEvent.showText(Component.text(desc)))
                .clickEvent(ClickEvent.runCommand(cmd))
                .append(Component.text(" " + desc, NamedTextColor.GRAY));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (race(p) == null) sendMenu(p, true);
            else if (job(p) == null) sendMenu(p, false);
        }, 40L);
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (!(s instanceof Player p)) return true;
        String n = c.getName();
        if (n.equals("race")) { sendMenu(p, true); return true; }
        if (n.equals("job")) { sendMenu(p, false); return true; }
        if (a.length == 0) {
            p.sendMessage(Component.text("종족: " + (race(p) == null ? "미선택" : race(p).name)
                    + " / 직업: " + (job(p) == null ? "미선택" : job(p).name + " t" + tier(p)), NamedTextColor.YELLOW));
            return true;
        }
        if (a[0].equalsIgnoreCase("give") && p.isOp() && a.length >= 2) {
            p.getInventory().addItem(special(a[1]));
            return true;
        }
        if (a[0].equalsIgnoreCase("pick") && a.length >= 3) {
            try {
                if (a[1].equals("race")) pick(p, Race.valueOf(a[2]));
                else pick(p, Job.valueOf(a[2]));
            } catch (IllegalArgumentException ex) { p.sendMessage("잘못된 선택입니다."); }
        }
        return true;
    }

    // ───────── 선택 처리 ─────────
    boolean consumeTicket(Player p, String type) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (!type.equals(itemType(hand))) return false;
        hand.setAmount(hand.getAmount() - 1);
        return true;
    }

    void pick(Player p, Race r) {
        if (race(p) != null && !consumeTicket(p, "race_ticket")) {
            p.sendMessage(Component.text("이미 종족을 선택했습니다. 종족 선택권을 손에 들고 다시 선택하세요.", NamedTextColor.RED));
            return;
        }
        p.getPersistentDataContainer().set(K_RACE, PersistentDataType.STRING, r.name());
        if (r == Race.BIRD) {
            ItemStack el = new ItemStack(Material.ELYTRA);
            ItemMeta m = el.getItemMeta();
            m.addEnchant(Enchantment.BINDING_CURSE, 1, true);
            el.setItemMeta(m);
            if (p.getInventory().getChestplate() == null) p.getInventory().setChestplate(el);
            else p.getInventory().addItem(el);
        }
        p.sendMessage(Component.text("종족이 [" + r.name + "](으)로 설정되었습니다.", NamedTextColor.GREEN));
        applyRace(p);
        if (job(p) == null) sendMenu(p, false);
    }

    void pick(Player p, Job j) {
        if (race(p) == null) { p.sendMessage("먼저 종족을 선택하세요."); return; }
        if (job(p) != null && !consumeTicket(p, "job_ticket")) {
            p.sendMessage(Component.text("이미 직업을 선택했습니다. 직업 선택권을 손에 들고 다시 선택하세요.", NamedTextColor.RED));
            return;
        }
        // 이전 직업 정리
        if (job(p) == Job.MUSICIAN && p.getInventory().getHelmet() != null
                && p.getInventory().getHelmet().getType() == Material.JUKEBOX)
            p.getInventory().setHelmet(null);
        p.getPersistentDataContainer().set(K_JOB, PersistentDataType.STRING, j.name());
        p.getPersistentDataContainer().set(K_TIER, PersistentDataType.INTEGER, 0);
        switch (j) {
            case SWORDSMAN -> p.getInventory().addItem(new ItemStack(Material.STONE_SWORD));
            case ARCHER -> { p.getInventory().addItem(new ItemStack(Material.BOW)); p.getInventory().addItem(new ItemStack(Material.ARROW, 32)); }
            case MUSICIAN -> {
                ItemStack old = p.getInventory().getHelmet();
                p.getInventory().setHelmet(new ItemStack(Material.JUKEBOX));
                if (old != null) p.getInventory().addItem(old);
            }
            default -> {}
        }
        p.sendMessage(Component.text("직업이 [" + j.name + "](으)로 설정되었습니다.", NamedTextColor.GOLD));
    }

    // ───────── 종족 효과 (1초마다 갱신) ─────────
    void setMod(Player p, Attribute attr, double amount, AttributeModifier.Operation op) {
        AttributeInstance inst = p.getAttribute(attr);
        if (inst == null) return;
        NamespacedKey key = new NamespacedKey(this, "mod_" + attr.getKey().getKey());
        inst.removeModifier(key);
        if (amount != 0) inst.addModifier(new AttributeModifier(key, amount, op, EquipmentSlotGroup.ANY));
    }
    void effect(Player p, PotionEffectType t, int amp) {
        p.addPotionEffect(new PotionEffect(t, 60, amp, true, false, false));
    }

    void applyRace(Player p) {
        Race r = race(p);
        if (r == null) r = Race.HUMAN;
        double hp = 0, speed = 0, atk = 0, scale = 0, mine = 0, jump = 0;
        switch (r) {
            case MERFOLK -> {
                if (p.isInWater()) {
                    atk = 1.0; mine = 1.0;
                    effect(p, PotionEffectType.WATER_BREATHING, 0);
                    effect(p, PotionEffectType.RESISTANCE, 0);
                } else { hp = -16; speed = -0.5; }
            }
            case BIRD -> hp = -10;
            case PERI -> { speed = 0.2; jump = 0.2; }
            case VAMPIRE -> {
                World w = p.getWorld();
                boolean day = w.getEnvironment() == World.Environment.NORMAL && w.getTime() < 12300;
                if (day) {
                    boolean exposed = p.getLocation().getBlock().getLightFromSky() >= 15
                            && !p.isInWater() && !w.hasStorm();
                    if (exposed) p.setFireTicks(60);
                } else speed = 0.3;
            }
            case BLIND -> {
                atk = 1.0;
                effect(p, PotionEffectType.RESISTANCE, 1);
                p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 120, 0, true, false, false));
            }
            case GIANT -> { atk = 1.0; speed = 0.5; scale = 1.0; effect(p, PotionEffectType.RESISTANCE, 1); }
            case DWARF -> { atk = -0.5; speed = 0.5; scale = -0.5; }
            default -> {}
        }
        setMod(p, Attribute.MAX_HEALTH, hp, AttributeModifier.Operation.ADD_NUMBER);
        setMod(p, Attribute.MOVEMENT_SPEED, speed, AttributeModifier.Operation.ADD_SCALAR);
        setMod(p, Attribute.ATTACK_DAMAGE, atk, AttributeModifier.Operation.ADD_SCALAR);
        setMod(p, Attribute.SCALE, scale, AttributeModifier.Operation.ADD_SCALAR);
        setMod(p, Attribute.SUBMERGED_MINING_SPEED, mine, AttributeModifier.Operation.ADD_SCALAR);
        setMod(p, Attribute.JUMP_STRENGTH, jump, AttributeModifier.Operation.ADD_SCALAR);
        double max = p.getAttribute(Attribute.MAX_HEALTH).getValue();
        if (p.getHealth() > max) p.setHealth(max);
    }

    void tick() {
        counter++;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR) continue;
            applyRace(p);
            if (job(p) == Job.MUSICIAN) {
                int interval = MUSIC_INTERVAL[tier(p)];
                if (counter % interval == 0) pulse(p);
            }
        }
    }

    void pulse(Player p) {
        p.getWorld().playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HARP, 1f, 1f);
        p.getWorld().spawnParticle(Particle.NOTE, p.getLocation().add(0, 2, 0), 12, 1.2, 0.3, 1.2);
        for (Entity en : p.getNearbyEntities(3, 3, 3)) {
            if (en instanceof Player t && t.getGameMode() == GameMode.SURVIVAL) {
                double left = t.getHealth() - 1.0; // 0.5하트, 방어 무시
                if (left <= 0) { reentry = true; t.damage(9999, p); reentry = false; }
                else t.setHealth(left);
            }
        }
    }

    // ───────── 전투 ─────────
    boolean isSword(Material m) { return m.name().endsWith("_SWORD"); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (reentry || !(e.getEntity() instanceof LivingEntity target)) return;
        Player p = null; boolean ranged = false;
        if (e.getDamager() instanceof Player pl) p = pl;
        else if (e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player pl) { p = pl; ranged = true; }
        if (p == null) return;

        double dmg = e.getDamage();
        Job j = job(p);
        int t = tier(p);
        if (j == null) j = Job.NONE;

        // 음악가 버프
        if (j == Job.MUSICIAN && buffUntil.getOrDefault(p.getUniqueId(), 0L) > System.currentTimeMillis()) dmg *= 1.2;

        switch (j) {
            case SWORDSMAN -> {
                if (!ranged && isSword(p.getInventory().getItemInMainHand().getType())) {
                    dmg *= 1 + SWORD[t];
                    repeat(p, target, dmg, new long[]{20}, false);
                }
            }
            case ARCHER -> { if (ranged) dmg *= 1 + BOW[t]; }
            case BRAWLER -> {
                if (!ranged && p.getInventory().getItemInMainHand().getType() == Material.AIR) {
                    dmg *= 1 + FIST[t];
                    repeat(p, target, dmg, new long[]{30, 60}, true);
                }
            }
            default -> {}
        }
        e.setDamage(dmg);

        // 흡혈귀 회복
        if (!ranged && race(p) == Race.VAMPIRE) {
            double max = p.getAttribute(Attribute.MAX_HEALTH).getValue();
            p.setHealth(Math.min(max, p.getHealth() + 1.0));
        }
    }

    void repeat(Player p, LivingEntity target, double dmg, long[] delays, boolean knock) {
        for (long d : delays) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (!p.isOnline() || target.isDead() || !target.isValid()) return;
                reentry = true;
                target.setNoDamageTicks(0);
                target.damage(dmg, p);
                reentry = false;
                if (knock) {
                    Vector v = target.getLocation().toVector().subtract(p.getLocation().toVector()).setY(0);
                    if (v.lengthSquared() > 0) target.setVelocity(v.normalize().multiply(0.5).setY(0.25));
                }
            }, d);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent e) {
        if (!(e.getEntity() instanceof Player p) || job(p) != Job.ARCHER) return;
        if (!(e.getProjectile() instanceof AbstractArrow base)) return;
        Vector v = base.getVelocity();
        for (double ang : new double[]{-8, 8}) {
            Arrow extra = p.getWorld().spawnArrow(base.getLocation(), v.clone().rotateAroundY(Math.toRadians(ang)), 1f, 0f);
            extra.setVelocity(v.clone().rotateAroundY(Math.toRadians(ang)));
            extra.setShooter(p);
            extra.setCritical(base.isCritical());
            extra.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamaged(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Race r = race(p);
        if (r == null) return;
        EntityDamageEvent.DamageCause c = e.getCause();
        if (c == EntityDamageEvent.DamageCause.FALL) {
            if (r == Race.BIRD) e.setCancelled(true);
            else if (r == Race.DWARF) e.setDamage(e.getDamage() * 2);
        }
        if (r == Race.VAMPIRE && (c == EntityDamageEvent.DamageCause.FIRE || c == EntityDamageEvent.DamageCause.FIRE_TICK
                || c == EntityDamageEvent.DamageCause.LAVA || c == EntityDamageEvent.DamageCause.HOT_FLOOR))
            e.setDamage(e.getDamage() * 3);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEat(PlayerItemConsumeEvent e) {
        if (race(e.getPlayer()) != Race.VAMPIRE) return;
        Material m = e.getItem().getType();
        if (m.name().startsWith("COOKED_") || m == Material.BAKED_POTATO || m == Material.RABBIT_STEW) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(Component.text("흡혈귀는 익힌 고기를 먹을 수 없습니다.", NamedTextColor.RED));
        }
    }

    @EventHandler
    public void onChat(AsyncChatEvent e) {
        if (race(e.getPlayer()) == Race.PERI) e.message(e.message().append(Component.text(" meow")));
    }

    // ───────── 아이템 사용: 전직의 별 / 음악가 버프 ─────────
    @EventHandler
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        ItemStack hand = p.getInventory().getItemInMainHand();

        if ("class_star".equals(itemType(hand))) {
            e.setCancelled(true);
            Job j = job(p);
            int t = tier(p);
            if (j == null || j == Job.NONE) { p.sendMessage(Component.text("직업이 없습니다.", NamedTextColor.RED)); return; }
            if (t >= 3) { p.sendMessage(Component.text("이미 최고 단계(t3)입니다.", NamedTextColor.RED)); return; }
            hand.setAmount(hand.getAmount() - 1);
            p.getPersistentDataContainer().set(K_TIER, PersistentDataType.INTEGER, t + 1);
            Firework fw = p.getWorld().spawn(p.getLocation(), Firework.class);
            org.bukkit.inventory.meta.FireworkMeta fm = fw.getFireworkMeta();
            fm.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.BALL_LARGE).withColor(Color.YELLOW).build());
            fw.setFireworkMeta(fm);
            fw.detonate();
            Bukkit.broadcast(Component.text("폭죽이 터지며 " + p.getName() + "님이 전직 (t" + (t + 1) + ")에 성공했습니다!", NamedTextColor.YELLOW));
            return;
        }

        if (job(p) == Job.MUSICIAN && p.isSneaking() && hand.getType() == Material.AIR) {
            long now = System.currentTimeMillis();
            if (buffCooldown.getOrDefault(p.getUniqueId(), 0L) > now) return;
            buffUntil.put(p.getUniqueId(), now + 5000);
            buffCooldown.put(p.getUniqueId(), now + 30000);
            p.getWorld().playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1f, 1.5f);
            p.sendMessage(Component.text("주크박스 울림! 5초간 공격력 +20%", NamedTextColor.LIGHT_PURPLE));
        }
    }

    // 선택권/별이 일반 종이·재료로 쓰이는 것 방지 (제작 재료로 쓰는 경우는 허용 안 함)
    @EventHandler
    public void onPrepare(PrepareItemCraftEvent e) {
        if (e.getRecipe() == null) return;
        for (ItemStack it : e.getInventory().getMatrix())
            if (it != null && itemType(it) != null) { e.getInventory().setResult(null); return; }
    }
}
