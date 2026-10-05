package com.theages.server.world;

import com.theages.protocol.v1.ServerMessage;

/**
 * 區域看到的玩家連線。實作必須可以從 tick 執行緒安全地呼叫，且不能阻塞。
 * 以物件身分（identity）作為連線的識別。
 */
public interface PlayerConnection {

    void send(ServerMessage message);

    /** 由伺服器端主動斷線，例如同一角色從別處登入。 */
    void close(String reason);
}
