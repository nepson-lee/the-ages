package com.theages.server.character;

import com.theages.server.world.CharacterSnapshot;
import com.theages.server.world.CharacterStore;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 在專用執行緒上把角色狀態寫回資料庫，讓區域 tick 不必等待 I/O。
 * 單一執行緒確保同一角色的存檔依序寫入。
 */
@Component
public class CharacterPersistence implements CharacterStore {

    private static final Logger log = LoggerFactory.getLogger(CharacterPersistence.class);

    private final PlayerCharacterRepository characters;
    private final CharacterItemRepository items;
    private final CharacterQuestRepository quests;
    private final CharacterSkillRepository skills;
    private final TransactionTemplate tx;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "character-persistence"));

    public CharacterPersistence(PlayerCharacterRepository characters, CharacterItemRepository items,
                                CharacterQuestRepository quests, CharacterSkillRepository skills,
                                TransactionTemplate tx) {
        this.characters = characters;
        this.items = items;
        this.quests = quests;
        this.skills = skills;
        this.tx = tx;
    }

    @Override
    public void saveAsync(CharacterSnapshot s) {
        executor.execute(() -> {
            try {
                // 角色、物品、任務、技能在同一個交易內寫入，避免只存到一半
                tx.executeWithoutResult(status -> characters.findById(s.characterId()).ifPresent(c -> {
                    c.update(s.zoneId(), s.x(), s.z(), s.level(), s.exp(), s.hp(), s.gold(), s.mp());
                    items.replaceAll(c.getId(), s.items());
                    quests.replaceAll(c.getId(), s.quests());
                    skills.replaceAll(c.getId(), s.skills());
                }));
            } catch (RuntimeException e) {
                log.error("角色 {} 存檔失敗", s.characterId(), e);
            }
        });
    }

    /** World（SmartLifecycle）會先停止並送出最後的存檔，這裡等它們寫完。 */
    @PreDestroy
    void drain() throws InterruptedException {
        executor.shutdown();
        if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
            log.warn("關機時仍有角色存檔未完成");
        }
    }
}
