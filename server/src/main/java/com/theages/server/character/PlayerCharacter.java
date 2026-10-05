package com.theages.server.character;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "player_character")
public class PlayerCharacter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false, unique = true)
    private Long accountId;

    @Column(nullable = false, unique = true, length = 32)
    private String name;

    @Column(name = "zone_id", nullable = false, length = 64)
    private String zoneId;

    @Column(name = "pos_x", nullable = false)
    private float posX;

    @Column(name = "pos_z", nullable = false)
    private float posZ;

    @Column(nullable = false)
    private int level = 1;

    @Column(nullable = false)
    private int exp;

    /** 0 代表滿血。 */
    @Column(nullable = false)
    private int hp;

    @Column(nullable = false)
    private int gold;

    /** -1 代表滿。 */
    @Column(nullable = false)
    private int mp = -1;

    protected PlayerCharacter() {
    }

    public PlayerCharacter(Long accountId, String name, String zoneId, float posX, float posZ, int gold) {
        this.accountId = accountId;
        this.name = name;
        this.zoneId = zoneId;
        this.posX = posX;
        this.posZ = posZ;
        this.gold = gold;
    }

    public Long getId() {
        return id;
    }

    public Long getAccountId() {
        return accountId;
    }

    public String getName() {
        return name;
    }

    public String getZoneId() {
        return zoneId;
    }

    public float getPosX() {
        return posX;
    }

    public float getPosZ() {
        return posZ;
    }

    public int getLevel() {
        return level;
    }

    public int getExp() {
        return exp;
    }

    public int getHp() {
        return hp;
    }

    public int getGold() {
        return gold;
    }

    public int getMp() {
        return mp;
    }

    public void update(String zoneId, float posX, float posZ, int level, int exp, int hp, int gold, int mp) {
        this.zoneId = zoneId;
        this.posX = posX;
        this.posZ = posZ;
        this.level = level;
        this.exp = exp;
        this.hp = hp;
        this.gold = gold;
        this.mp = mp;
    }
}
