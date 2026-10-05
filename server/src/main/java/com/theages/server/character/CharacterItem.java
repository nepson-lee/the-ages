package com.theages.server.character;

import com.theages.server.world.item.ItemRecord;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "character_item")
public class CharacterItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "character_id", nullable = false)
    private Long characterId;

    @Column(name = "template_id", nullable = false, length = 64)
    private String templateId;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false)
    private boolean equipped;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    protected CharacterItem() {
    }

    public CharacterItem(Long characterId, ItemRecord record, int sortOrder) {
        this.characterId = characterId;
        this.templateId = record.templateId();
        this.quantity = record.quantity();
        this.equipped = record.equipped();
        this.sortOrder = sortOrder;
    }

    public ItemRecord toRecord() {
        return new ItemRecord(templateId, quantity, equipped);
    }
}
