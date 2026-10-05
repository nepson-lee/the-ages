package com.theages.server.world.quest;

import static org.assertj.core.api.Assertions.assertThat;

import com.theages.server.world.item.Inventory;
import com.theages.server.world.item.ItemTemplate;
import com.theages.server.world.item.ItemType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QuestLogTest {

    static final ItemTemplate FUR = new ItemTemplate("fur", "兔毛", List.of(), "", ItemType.MISC, null, 0, 0, 0, 0, 1, null);

    static final QuestDefinition HUNT = new QuestDefinition("hunt", "打獵", "elder", null, "", 1, List.of(),
        List.of(new QuestDefinition.Objective("rabbit", null, 2), new QuestDefinition.Objective(null, "fur", 3)),
        null, false, null, null);
    static final QuestDefinition NEXT = new QuestDefinition("next", "下一步", "elder", null, "", 3, List.of("hunt"),
        List.of(), null, false, null, null);
    static final QuestDefinition DAILY = new QuestDefinition("daily", "每日", "elder", null, "", 1, List.of(),
        List.of(), null, true, null, null);

    @Test
    void tracksKillsAndCollectsFromInventory() {
        QuestLog log = new QuestLog();
        Inventory inv = new Inventory();
        log.accept(HUNT);
        QuestLog.ActiveQuest a = log.active("hunt").orElseThrow();

        assertThat(log.onKill("wolf")).isFalse();
        assertThat(log.onKill("rabbit")).isTrue();
        log.onKill("rabbit");
        assertThat(log.onKill("rabbit")).as("滿了就不再累計").isFalse();
        assertThat(log.progress(a, 0, inv)).isEqualTo(2);

        inv.add(FUR, 5);
        assertThat(log.progress(a, 1, inv)).as("不超過需求數").isEqualTo(3);
        assertThat(log.isReady(a, inv)).isTrue();
    }

    @Test
    void acceptRules() {
        QuestLog log = new QuestLog();
        assertThat(log.whyCannotAccept(NEXT, 5)).hasValue("你還沒有資格接「下一步」。");

        log.accept(HUNT);
        assertThat(log.whyCannotAccept(HUNT, 1)).hasValue("你已經接了「打獵」。");
        log.complete("hunt");
        assertThat(log.whyCannotAccept(HUNT, 1)).hasValue("你已經完成過「打獵」了。");
        assertThat(log.whyCannotAccept(NEXT, 2)).hasValue("「下一步」需要 3 級才能接。");
        assertThat(log.isAvailable(NEXT, 3)).isTrue();
    }

    @Test
    void repeatableQuestsCanBeTakenAgain() {
        QuestLog log = new QuestLog();
        log.accept(DAILY);
        log.complete("daily");

        assertThat(log.isCompleted("daily")).isFalse();
        assertThat(log.isAvailable(DAILY, 1)).isTrue();
    }

    @Test
    void activeQuestLimit() {
        QuestLog log = new QuestLog();
        for (int i = 0; i < QuestLog.MAX_ACTIVE; i++) {
            log.accept(new QuestDefinition("q" + i, "任務" + i, "elder", null, "", 1, List.of(), List.of(), null,
                false, null, null));
        }
        assertThat(log.whyCannotAccept(DAILY, 1)).hasValueSatisfying(why -> assertThat(why).contains("太多了"));
    }

    @Test
    void roundTripsThroughRecordsAndSkipsUnknownQuests() {
        QuestLog log = new QuestLog();
        log.accept(HUNT);
        log.onKill("rabbit");
        log.accept(DAILY);
        log.complete("daily");
        log.accept(NEXT); // 不檢查資格，直接放進去測存檔
        log.complete("next");

        List<QuestRecord> records = new ArrayList<>(log.toRecords());
        records.add(new QuestRecord("deleted", false, "1"));
        List<String> unknown = new ArrayList<>();
        QuestLog restored = QuestLog.fromRecords(records, Map.of("hunt", HUNT, "next", NEXT, "daily", DAILY), unknown::add);

        assertThat(restored.toRecords()).isEqualTo(log.toRecords());
        assertThat(restored.isCompleted("next")).isTrue();
        assertThat(restored.progress(restored.active("hunt").orElseThrow(), 0, new Inventory())).isEqualTo(1);
        assertThat(unknown).containsExactly("deleted");
    }

    @Test
    void corruptProgressIsTreatedAsZero() {
        QuestLog log = QuestLog.fromRecords(List.of(new QuestRecord("hunt", false, "x,2,9")), Map.of("hunt", HUNT), id -> { });
        QuestLog.ActiveQuest a = log.active("hunt").orElseThrow();

        assertThat(log.progress(a, 0, new Inventory())).isZero();
    }

    @Test
    void objectiveNeedsExactlyOneTarget() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new QuestDefinition.Objective(null, null, 1))
            .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new QuestDefinition.Objective("a", "b", 1))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
