package com.theages.server.world.skill;

public enum SkillType {
    /** 單體攻擊：打目前的戰鬥對象，必中。 */
    STRIKE,
    /** 範圍攻擊：打身邊所有敵人。 */
    AOE,
    /** 治療：自己或指定的玩家。 */
    HEAL,
    /** 增益：一段時間內提升攻擊或防禦。 */
    BUFF
}
