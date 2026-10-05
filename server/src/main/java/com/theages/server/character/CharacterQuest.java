package com.theages.server.character;

import com.theages.server.world.quest.QuestRecord;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "character_quest")
public class CharacterQuest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "character_id", nullable = false)
    private Long characterId;

    @Column(name = "quest_id", nullable = false, length = 64)
    private String questId;

    @Column(nullable = false)
    private boolean completed;

    @Column(nullable = false, length = 64)
    private String progress;

    protected CharacterQuest() {
    }

    public CharacterQuest(Long characterId, QuestRecord record) {
        this.characterId = characterId;
        this.questId = record.questId();
        this.completed = record.completed();
        this.progress = record.progress();
    }

    public QuestRecord toRecord() {
        return new QuestRecord(questId, completed, progress);
    }
}
