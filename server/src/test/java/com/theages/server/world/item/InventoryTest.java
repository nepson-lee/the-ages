package com.theages.server.world.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InventoryTest {

    static final ItemTemplate SWORD = new ItemTemplate("sword", "木劍", List.of("sword"), "", ItemType.EQUIPMENT,
        EquipSlot.WEAPON, 2, 0, 0, 0, null);
    static final ItemTemplate DAGGER = new ItemTemplate("dagger", "匕首", List.of("dagger"), "", ItemType.EQUIPMENT,
        EquipSlot.WEAPON, 6, 0, 0, 0, null);
    static final ItemTemplate VEST = new ItemTemplate("vest", "背心", List.of("vest"), "", ItemType.EQUIPMENT,
        EquipSlot.BODY, 0, 4, 10, 0, null);
    static final ItemTemplate MEAT = new ItemTemplate("meat", "兔肉", List.of("meat"), "", ItemType.CONSUMABLE,
        null, 0, 0, 0, 15, 5);

    @Test
    void stacksUpToMaxStackThenOpensNewSlots() {
        Inventory inv = new Inventory();
        inv.add(MEAT, 3);
        inv.add(MEAT, 4);

        assertThat(inv.entries()).extracting(InventoryEntry::quantity).containsExactly(5, 2);
    }

    @Test
    void refusesWhenFull() {
        Inventory inv = new Inventory();
        for (int i = 0; i < Inventory.CAPACITY; i++) {
            inv.add(SWORD, 1);
        }
        assertThat(inv.canAdd(SWORD, 1)).isFalse();
        assertThat(inv.canAdd(MEAT, 1)).isFalse();
        assertThatThrownBy(() -> inv.add(MEAT, 1)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void partialStackStillHasRoomWhenFull() {
        Inventory inv = new Inventory();
        inv.add(MEAT, 1);
        for (int i = 1; i < Inventory.CAPACITY; i++) {
            inv.add(SWORD, 1);
        }
        assertThat(inv.canAdd(MEAT, 4)).isTrue();
        assertThat(inv.canAdd(MEAT, 5)).isFalse();
    }

    @Test
    void equippingSwapsOutPreviousItemInSameSlot() {
        Inventory inv = new Inventory();
        inv.add(SWORD, 1);
        inv.add(DAGGER, 1);
        InventoryEntry sword = inv.find("sword", false).orElseThrow();
        InventoryEntry dagger = inv.find("dagger", false).orElseThrow();

        inv.equip(sword);
        assertThat(inv.equip(dagger)).contains(sword);
        assertThat(sword.equipped()).isFalse();
        assertThat(inv.bonusAttack()).isEqualTo(6);
    }

    @Test
    void sumsBonusesOfEquippedItemsOnly() {
        Inventory inv = new Inventory();
        inv.add(SWORD, 1);
        inv.add(VEST, 1);
        inv.add(DAGGER, 1);
        inv.equip(inv.find("sword", false).orElseThrow());
        inv.equip(inv.find("vest", false).orElseThrow());

        assertThat(inv.bonusAttack()).isEqualTo(2);
        assertThat(inv.bonusDefense()).isEqualTo(4);
        assertThat(inv.bonusMaxHp()).isEqualTo(10);
    }

    @Test
    void findsByUidNameOrKeyword() {
        Inventory inv = new Inventory();
        inv.add(SWORD, 1);
        inv.add(MEAT, 2);
        int meatUid = inv.entries().get(1).uid();

        assertThat(inv.find("#" + meatUid, false)).map(e -> e.template().id()).contains("meat");
        assertThat(inv.find("兔肉", false)).isPresent();
        assertThat(inv.find("MEAT", false)).isPresent();
        assertThat(inv.find("#abc", false)).isEmpty();
        assertThat(inv.find("nothing", false)).isEmpty();
    }

    @Test
    void preferEquippedPicksTheWornCopy() {
        Inventory inv = new Inventory();
        inv.add(SWORD, 1);
        inv.add(SWORD, 1);
        InventoryEntry second = inv.entries().get(1);
        inv.equip(second);

        assertThat(inv.find("sword", true)).contains(second);
        assertThat(inv.find("sword", false)).contains(inv.entries().get(0));
    }

    @Test
    void roundTripsThroughRecordsAndSkipsUnknownTemplates() {
        Inventory inv = new Inventory();
        inv.add(SWORD, 1);
        inv.add(MEAT, 3);
        inv.equip(inv.find("sword", false).orElseThrow());

        List<ItemRecord> records = new ArrayList<>(inv.toRecords());
        records.add(new ItemRecord("deleted-item", 1, false));
        List<String> unknown = new ArrayList<>();
        Inventory restored = Inventory.fromRecords(records, Map.of("sword", SWORD, "meat", MEAT), unknown::add);

        assertThat(restored.toRecords()).isEqualTo(inv.toRecords());
        assertThat(unknown).containsExactly("deleted-item");
    }

    @Test
    void removingLastOfAStackRemovesTheSlot() {
        Inventory inv = new Inventory();
        inv.add(MEAT, 2);
        InventoryEntry meat = inv.entries().get(0);

        inv.remove(meat, 1);
        assertThat(meat.quantity()).isEqualTo(1);
        inv.remove(meat, 1);
        assertThat(inv.entries()).isEmpty();
    }

    @Test
    void equipmentNeverStacks() {
        assertThat(SWORD.maxStack()).isEqualTo(1);
        assertThatThrownBy(() -> new ItemTemplate("x", "x", null, "", ItemType.EQUIPMENT, null, 0, 0, 0, 0, null))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
