package com.theages.server.world.item;

public enum EquipSlot {
    WEAPON("武器"),
    HEAD("頭部"),
    BODY("身體"),
    FEET("腳部");

    private final String label;

    EquipSlot(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
