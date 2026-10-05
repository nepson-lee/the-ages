package com.theages.server.world.item;

/** 背包中的一格。uid 只在本次連線內有意義，不存檔。 */
public final class InventoryEntry {

    private final int uid;
    private final ItemTemplate template;
    private int quantity;
    private boolean equipped;

    InventoryEntry(int uid, ItemTemplate template, int quantity) {
        this.uid = uid;
        this.template = template;
        this.quantity = quantity;
    }

    public int uid() {
        return uid;
    }

    public ItemTemplate template() {
        return template;
    }

    public int quantity() {
        return quantity;
    }

    public boolean equipped() {
        return equipped;
    }

    void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    void setEquipped(boolean equipped) {
        this.equipped = equipped;
    }

    /** 例如「野兔肉 ×3」。 */
    public String displayName() {
        return quantity > 1 ? template.name() + " ×" + quantity : template.name();
    }
}
