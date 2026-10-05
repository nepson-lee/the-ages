package com.theages.server.world;

import com.theages.protocol.v1.EntityKind;
import com.theages.protocol.v1.SelfStats;
import com.theages.server.world.item.Inventory;
import com.theages.server.world.quest.QuestLog;
import java.util.List;

/** 區域內的玩家。只能在所屬區域的 tick 執行緒存取。 */
public final class PlayerEntity extends Entity {

    /** 移動速度（公尺/秒）。比大部分 NPC 快，打不贏可以跑。 */
    static final float SPEED = 5f;

    private final long characterId;
    private final Inventory inventory;
    private final QuestLog quests;
    private PlayerConnection connection;
    private int exp;
    private int gold;

    /** 正在走過去撿的地上物品；null = 沒有。 */
    GroundItem pendingPickup;
    /** 正在走過去交易的商人；null = 沒有。 */
    NpcEntity pendingShop;
    /** 目前開著商店畫面的商人；null = 沒開。 */
    NpcEntity openShop;
    /** 正在走過去的出口；null = 沒有。 */
    Portal pendingExit;
    /** 走到 pendingShop 之後要做的事：true = 對話（任務），false = 打開商店。 */
    boolean pendingTalk;
    /** 上一次送出的任務標記，沒變就不重送。 */
    List<com.theages.protocol.v1.QuestMarker> lastQuestMarkers = List.of();
    /** 下一次可以使用消耗品的 tick。 */
    long nextUseTick;

    PlayerEntity(int id, long characterId, String name, PlayerConnection connection, float x, float z,
                 int level, int exp, int hp, int gold, Inventory inventory, QuestLog quests) {
        super(id, name, x, z);
        this.characterId = characterId;
        this.connection = connection;
        this.exp = exp;
        this.gold = Math.max(0, gold);
        this.inventory = inventory;
        this.quests = quests;
        applyLevel(Math.max(1, level));
        setHp(hp <= 0 ? maxHp : hp);
    }

    PlayerEntity(int id, long characterId, String name, PlayerConnection connection, float x, float z,
                 int level, int exp, int hp) {
        this(id, characterId, name, connection, x, z, level, exp, hp, 0, new Inventory(), new QuestLog());
    }

    // ===== 等級公式（之後可移到內容檔） =====

    static int maxHpFor(int level) {
        return 50 + (level - 1) * 12;
    }

    static int attackFor(int level) {
        return 8 + (level - 1) * 2;
    }

    static int defenseFor(int level) {
        return 2 + (level - 1);
    }

    static int expToNext(int level) {
        return level * 100;
    }

    private void applyLevel(int newLevel) {
        level = newLevel;
        refreshStats();
    }

    /** 數值 = 等級基礎值 + 裝備加成。換裝備後呼叫。 */
    void refreshStats() {
        maxHp = maxHpFor(level) + inventory.bonusMaxHp();
        attack = attackFor(level) + inventory.bonusAttack();
        defense = defenseFor(level) + inventory.bonusDefense();
        setHp(hp()); // 生命上限變低時一併降低目前生命
        markStatsDirty();
    }

    /** 增加經驗值，回傳升了幾級。升級時補滿 HP。 */
    int gainExp(int amount) {
        exp += amount;
        int levels = 0;
        while (exp >= expToNext(level)) {
            exp -= expToNext(level);
            applyLevel(level + 1);
            levels++;
        }
        if (levels > 0) {
            setHp(maxHp);
        }
        markStatsDirty();
        return levels;
    }

    /** 死亡懲罰：扣掉本級 10% 經驗值（不會降級），回傳扣了多少。 */
    int loseExpOnDeath() {
        int lost = Math.min(exp, expToNext(level) / 10);
        exp -= lost;
        markStatsDirty();
        return lost;
    }

    void addGold(int amount) {
        gold += amount;
        markStatsDirty();
    }

    /** 扣錢；不夠就不扣並回傳 false。 */
    boolean spendGold(int amount) {
        if (amount > gold) {
            return false;
        }
        gold -= amount;
        markStatsDirty();
        return true;
    }

    SelfStats toSelfStats() {
        return SelfStats.newBuilder()
            .setLevel(level)
            .setExp(exp)
            .setExpToNext(expToNext(level))
            .setHp(hp())
            .setMaxHp(maxHp)
            .setAttack(attack)
            .setDefense(defense)
            .setGold(gold)
            .build();
    }

    CharacterSnapshot snapshot(String zoneId) {
        return new CharacterSnapshot(characterId, zoneId, x(), z(), level, exp, hp(), gold, inventory.toRecords(),
            quests.toRecords());
    }

    void replaceConnection(PlayerConnection newConnection) {
        this.connection = newConnection;
        stop();
        markStatsDirty();
    }

    @Override
    EntityKind kind() {
        return EntityKind.ENTITY_KIND_PLAYER;
    }

    @Override
    String model() {
        return "player";
    }

    @Override
    float speed() {
        return SPEED;
    }

    public long characterId() {
        return characterId;
    }

    public Inventory inventory() {
        return inventory;
    }

    public QuestLog quests() {
        return quests;
    }

    public PlayerConnection connection() {
        return connection;
    }

    public int exp() {
        return exp;
    }

    public int gold() {
        return gold;
    }
}
