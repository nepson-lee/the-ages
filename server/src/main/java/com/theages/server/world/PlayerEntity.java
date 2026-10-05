package com.theages.server.world;

import com.theages.protocol.v1.EntityKind;
import com.theages.protocol.v1.SelfStats;

/** 區域內的玩家。只能在所屬區域的 tick 執行緒存取。 */
public final class PlayerEntity extends Entity {

    /** 移動速度（公尺/秒）。比大部分 NPC 快，打不贏可以跑。 */
    static final float SPEED = 5f;

    private final long characterId;
    private PlayerConnection connection;
    private int exp;

    PlayerEntity(int id, long characterId, String name, PlayerConnection connection, float x, float z,
                 int level, int exp, int hp) {
        super(id, name, x, z);
        this.characterId = characterId;
        this.connection = connection;
        this.exp = exp;
        applyLevel(Math.max(1, level));
        setHp(hp <= 0 ? maxHp : hp);
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
        maxHp = maxHpFor(newLevel);
        attack = attackFor(newLevel);
        defense = defenseFor(newLevel);
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

    SelfStats toSelfStats() {
        return SelfStats.newBuilder()
            .setLevel(level)
            .setExp(exp)
            .setExpToNext(expToNext(level))
            .setHp(hp())
            .setMaxHp(maxHp)
            .setAttack(attack)
            .setDefense(defense)
            .build();
    }

    CharacterSnapshot snapshot(String zoneId) {
        return new CharacterSnapshot(characterId, zoneId, x(), z(), level, exp, hp());
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

    public PlayerConnection connection() {
        return connection;
    }

    public int exp() {
        return exp;
    }
}
