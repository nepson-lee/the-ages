package com.theages.server.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 註冊、登入的頻率限制。
 *
 * @param loginPerIp     每個 IP 的登入嘗試（不論成功失敗）
 * @param registerPerIp  每個 IP 的註冊
 * @param maxFailures    同一帳號在 failureWindow 內失敗這麼多次就鎖住
 * @param failureWindow  計算失敗次數的時間窗
 * @param lockout        鎖住多久
 */
@ConfigurationProperties("theages.auth-limit")
public record AuthLimitProperties(Bucket loginPerIp, Bucket registerPerIp, int maxFailures, Duration failureWindow,
                                  Duration lockout) {

    /**
     * @param burst     最多可以連續幾次
     * @param perMinute 長時間平均每分鐘幾次
     */
    public record Bucket(int burst, double perMinute) {

        double perSecond() {
            return perMinute / 60.0;
        }
    }
}
