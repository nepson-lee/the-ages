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

/** 在專用執行緒上把角色狀態寫回資料庫，讓區域 tick 不必等待 I/O。 */
@Component
public class CharacterPersistence implements CharacterStore {

    private static final Logger log = LoggerFactory.getLogger(CharacterPersistence.class);

    private final PlayerCharacterRepository repository;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "character-persistence"));

    public CharacterPersistence(PlayerCharacterRepository repository) {
        this.repository = repository;
    }

    @Override
    public void saveAsync(CharacterSnapshot s) {
        executor.execute(() -> {
            try {
                repository.findById(s.characterId()).ifPresent(c -> {
                    c.update(s.zoneId(), s.x(), s.z(), s.level(), s.exp(), s.hp());
                    repository.save(c);
                });
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
