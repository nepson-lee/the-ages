# 時空之門 The Ages — 3D 網頁版

向台灣經典 MUD《時空之門》（4444）致敬的瀏覽器 3D 多人遊戲。

## 架構

```
client/    Vite + TypeScript + Three.js（靜態 SPA）
protocol/  Protobuf 訊息定義：前後端唯一的協定來源
server/    Java 17 + Spring Boot 4 + Maven
```

| 通道 | 用途 |
|---|---|
| REST `/api/auth/*` | 註冊、登入，回傳 JWT |
| WebSocket `/ws?token=<JWT>` | 進入遊戲後的即時訊息（Protobuf 二進位） |

伺服器端的分層：

- `auth`：帳號、JWT（Spring Security 的 OAuth2 resource server，HS256）。
- `gateway`：WebSocket 進出口。只負責解碼與驗證，再把事件丟進區域。
- `world`：遊戲核心，**不依賴 Spring**。每個區域（`Zone`）用一條執行緒跑固定頻率的 tick 迴圈，區域狀態只在該執行緒上讀寫，所以不需要鎖。
- `character`：角色存檔，在專用執行緒上非同步寫入資料庫。tick 執行緒不能等 I/O。

伺服器有最終決定權：客戶端只送「意圖」（`MoveTo`、`Command`），位置與結果一律由伺服器計算後廣播。

## 環境需求

- JDK 17 以上（升到 21 後可把 `spring.threads.virtual.enabled` 設成 `true`）
- Maven 3.9 以上
- Node.js 20 以上
- Docker（用來跑 PostgreSQL）

## 本機開發

```bash
# 1. 資料庫
docker compose up -d

# 2. 伺服器（http://localhost:8080）
mvn install -DskipTests
mvn -pl server spring-boot:run

# 3. 前端（http://localhost:5173，/api 與 /ws 會代理到 8080）
cd client
npm install
npm run dev
```

用兩個瀏覽器分頁註冊兩個角色，就能看到彼此移動。

**沒有 Docker 時**，可以改用 H2 記憶體資料庫啟動伺服器，重啟後資料會清空：

```bash
mvn -pl server spring-boot:test-run -Dspring-boot.run.profiles=test
```

## 測試

```bash
mvn verify            # 伺服器：區域邏輯單元測試 + 註冊/登入/WebSocket 整合測試（H2）
cd client && npm run build   # 前端：型別檢查 + 打包
```

## 修改協定

1. 編輯 `protocol/src/main/protobuf/theages/v1/game.proto`。
2. Java 端：`mvn install` 時會自動重新產生程式碼。
3. 前端：`npm run gen`（`dev` 和 `build` 會自動執行），輸出到 `client/src/gen/`（不進版控）。

已發佈的欄位編號不可重用或改型別；要刪除欄位請改用 `reserved`。

## 遊戲內指令

| 指令 | 說明 |
|---|---|
| `look`（`l`） | 觀察四周 |
| `say <內容>`（`'`） | 對區域內的人說話 |
| `who` | 列出區域內的玩家 |
| `help` | 列出所有指令 |

新增指令：在 `server/.../world/Commands.java` 的建構子裡 `register`。

## 設定

`server/src/main/resources/application.yml`：

- `theages.jwt.secret`：**正式環境必須用環境變數 `THEAGES_JWT_SECRET` 覆寫**（Base64，至少 32 bytes）。
- `theages.websocket.allowed-origins`：允許連線的前端網址。
- `theages.world.zones`：區域定義（名稱、描述、大小、出生點）。

## 已知限制（之後處理）

- 玩家斷線後立刻重連，可能讀到尚未寫入的舊位置（存檔是非同步的）。同一連線期間重複登入則不受影響，會沿用記憶體中的狀態。
- 指令與移動沒有頻率限制（rate limit）。
- 角色名稱等於帳號名稱，一個帳號一個角色。
- 場景是程式產生的佔位模型，之後換成 glTF。
- Redis 已放在 `docker-compose.yml`，但伺服器還沒使用。
