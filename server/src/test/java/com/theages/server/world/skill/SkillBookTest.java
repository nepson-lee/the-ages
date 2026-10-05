package com.theages.server.world.skill;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SkillBookTest {

    private static final int TICK_RATE = 10;
    static final SkillDefinition BASH = new SkillDefinition("bash", "重擊", List.of(), "", SkillType.STRIKE,
        1, "elder", 0, 8, 4, 1.8, 0, 0f, 0, 0, 0);
    static final SkillDefinition MEND = new SkillDefinition("mend", "療傷", List.of(), "", SkillType.HEAL,
        1, "elder", 0, 5, 8, 20, 4, 8f, 0, 0, 0);
    static final SkillDefinition IRON = new SkillDefinition("iron", "鐵布衫", List.of("iron"), "", SkillType.BUFF,
        1, "elder", 0, 5, 30, 0, 0, 0f, 2, 6, 20);

    @Test
    void cooldownsArePerSkillPlusAGlobalCooldown() {
        SkillBook book = new SkillBook();
        book.startCooldown(BASH, 100, TICK_RATE);

        assertThat(book.ticksUntilReady(BASH, 100)).isEqualTo(40);
        assertThat(book.ticksUntilReady(MEND, 100)).as("共用冷卻 1 秒").isEqualTo(10);
        assertThat(book.ticksUntilReady(MEND, 110)).isZero();
        assertThat(book.ticksUntilReady(BASH, 140)).isZero();
    }

    @Test
    void recastingABuffRefreshesInsteadOfStacking() {
        SkillBook book = new SkillBook();
        book.applyBuff(IRON, 0, TICK_RATE);
        book.applyBuff(IRON, 100, TICK_RATE);

        assertThat(book.buffDefense()).isEqualTo(6);
        assertThat(book.buffAttack()).isEqualTo(2);
        assertThat(book.expireBuffs(200)).as("原本 200 到期，刷新後延到 300").isEmpty();
        assertThat(book.expireBuffs(300)).hasSize(1);
        assertThat(book.buffDefense()).isZero();
    }

    @Test
    void findsByNameIdOrKeywordAndRoundTrips() {
        List<String> unknown = new ArrayList<>();
        SkillBook book = SkillBook.fromIds(List.of("iron", "gone"), Map.of("iron", IRON, "bash", BASH), unknown::add);

        assertThat(book.find("鐵布衫")).contains(IRON);
        assertThat(book.find("IRON")).contains(IRON);
        assertThat(book.find("bash")).isEmpty();
        assertThat(book.toIds()).containsExactly("iron");
        assertThat(unknown).containsExactly("gone");
    }

    @Test
    void healAmountScalesWithLevel() {
        assertThat(MEND.healAmount(1)).isEqualTo(24);
        assertThat(MEND.healAmount(5)).isEqualTo(40);
        assertThat(IRON.summary()).contains("攻擊 +2", "防禦 +6", "持續 20 秒", "內力 5");
    }
}
