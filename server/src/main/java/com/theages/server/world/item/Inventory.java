package com.theages.server.world.item;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * 玩家的背包與裝備。裝備中的物品也佔一格。
 * 只能在玩家所屬區域的 tick 執行緒存取。
 */
public final class Inventory {

    public static final int CAPACITY = 20;

    private final List<InventoryEntry> entries = new ArrayList<>();
    private int nextUid = 1;
    private boolean dirty = true;

    /**
     * 從存檔還原。內容檔已刪除的物品會被略過並回報給 onUnknown。
     */
    public static Inventory fromRecords(List<ItemRecord> records, Map<String, ItemTemplate> templates,
                                        Consumer<String> onUnknown) {
        Inventory inv = new Inventory();
        for (ItemRecord r : records) {
            ItemTemplate t = templates.get(r.templateId());
            if (t == null) {
                onUnknown.accept(r.templateId());
                continue;
            }
            InventoryEntry e = inv.newEntry(t, Math.min(r.quantity(), t.maxStack()));
            if (r.equipped() && t.isEquipment() && inv.equipped(t.slot()).isEmpty()) {
                e.setEquipped(true);
            }
        }
        return inv;
    }

    public List<ItemRecord> toRecords() {
        return entries.stream().map(e -> new ItemRecord(e.template().id(), e.quantity(), e.equipped())).toList();
    }

    public List<InventoryEntry> entries() {
        return Collections.unmodifiableList(entries);
    }

    /** 能否完整放入 quantity 個（優先疊到現有的格子）。 */
    public boolean canAdd(ItemTemplate template, int quantity) {
        int room = (CAPACITY - entries.size()) * template.maxStack();
        for (InventoryEntry e : entries) {
            if (e.template().equals(template) && !e.equipped()) {
                room += template.maxStack() - e.quantity();
            }
        }
        return room >= quantity;
    }

    /** 放入物品；呼叫前應先用 {@link #canAdd} 檢查。 */
    public void add(ItemTemplate template, int quantity) {
        if (!canAdd(template, quantity)) {
            throw new IllegalStateException("背包已滿");
        }
        int left = quantity;
        for (InventoryEntry e : entries) {
            if (left == 0) {
                break;
            }
            if (e.template().equals(template) && !e.equipped() && e.quantity() < template.maxStack()) {
                int put = Math.min(left, template.maxStack() - e.quantity());
                e.setQuantity(e.quantity() + put);
                left -= put;
            }
        }
        while (left > 0) {
            int put = Math.min(left, template.maxStack());
            newEntry(template, put);
            left -= put;
        }
        dirty = true;
    }

    /** 拿走 quantity 個（不超過該格數量），拿光時移除該格。裝備中的會先卸下。 */
    public void remove(InventoryEntry entry, int quantity) {
        int left = entry.quantity() - Math.min(quantity, entry.quantity());
        if (left == 0) {
            entries.remove(entry);
        } else {
            entry.setQuantity(left);
        }
        if (left == 0 && entry.equipped()) {
            entry.setEquipped(false);
        }
        dirty = true;
    }

    /**
     * 依查詢字串找一格：{@code #<uid>}、中文名稱或英文代稱。
     * 同名有多格時，preferEquipped 決定先找裝備中的還是背包裡的。
     */
    public Optional<InventoryEntry> find(String query, boolean preferEquipped) {
        if (query.startsWith("#")) {
            try {
                int uid = Integer.parseInt(query.substring(1));
                return entries.stream().filter(e -> e.uid() == uid).findFirst();
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }
        List<InventoryEntry> matches = entries.stream().filter(e -> e.template().matches(query)).toList();
        return matches.stream().filter(e -> e.equipped() == preferEquipped).findFirst()
            .or(() -> matches.stream().findFirst());
    }

    public Optional<InventoryEntry> equipped(EquipSlot slot) {
        return entries.stream().filter(e -> e.equipped() && e.template().slot() == slot).findFirst();
    }

    /** 穿上裝備，回傳被換下來的那件（若有）。 */
    public Optional<InventoryEntry> equip(InventoryEntry entry) {
        if (!entry.template().isEquipment() || !entries.contains(entry)) {
            throw new IllegalArgumentException(entry.template().id() + " 不是背包中的裝備");
        }
        Optional<InventoryEntry> previous = equipped(entry.template().slot()).filter(e -> e != entry);
        previous.ifPresent(e -> e.setEquipped(false));
        entry.setEquipped(true);
        dirty = true;
        return previous;
    }

    public void unequip(InventoryEntry entry) {
        entry.setEquipped(false);
        dirty = true;
    }

    public int bonusAttack() {
        return sumEquipped(ItemTemplate::attack);
    }

    public int bonusDefense() {
        return sumEquipped(ItemTemplate::defense);
    }

    public int bonusMaxHp() {
        return sumEquipped(ItemTemplate::maxHp);
    }

    public boolean consumeDirty() {
        boolean d = dirty;
        dirty = false;
        return d;
    }

    private int sumEquipped(java.util.function.ToIntFunction<ItemTemplate> stat) {
        return entries.stream().filter(InventoryEntry::equipped).mapToInt(e -> stat.applyAsInt(e.template())).sum();
    }

    private InventoryEntry newEntry(ItemTemplate template, int quantity) {
        InventoryEntry e = new InventoryEntry(nextUid++, template, quantity);
        entries.add(e);
        return e;
    }
}
