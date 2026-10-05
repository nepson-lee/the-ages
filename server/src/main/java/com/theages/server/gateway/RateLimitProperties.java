package com.theages.server.gateway;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 每條連線的訊息頻率限制。
 *
 * @param move              點擊移動
 * @param attack            點擊攻擊
 * @param command           文字指令（含聊天、技能、背包按鈕）
 * @param noticeInterval    「動作太快」提示的最短間隔
 * @param abuseWindow       濫用判定的時間窗
 * @param abuseRejections   時間窗內被擋這麼多次就斷線
 * @param maxMessageBytes   單則訊息的大小上限
 */
@ConfigurationProperties("theages.rate-limit")
public record RateLimitProperties(Bucket move, Bucket attack, Bucket command, Duration noticeInterval,
                                  Duration abuseWindow, int abuseRejections, int maxMessageBytes) {

    /**
     * @param burst     最多可以連續送幾則
     * @param perSecond 長時間平均每秒幾則
     */
    public record Bucket(int burst, double perSecond) {
    }
}
