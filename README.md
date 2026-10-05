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
| `look`（`l`） | 觀察四周；`look <目標>` 觀察某個生物 |
| `say <內容>`（`'`） | 對區域內的人說話 |
| `who` | 列出區域內的玩家 |
| `kill <目標>`（`k`） | 攻擊生物，例如 `kill rabbit`、`kill 野兔`；也可以直接點擊生物 |
| `flee` | 停止攻擊（對方可能還會追你） |
| `score`（`hp`、`sc`） | 查看等級、生命、攻防、經驗、銅錢 |
| `inventory`（`i`） | 查看背包，每格前面的 `#編號` 可以用來指定物品 |
| `equipment`（`eq`） | 查看身上的裝備 |
| `get <物品>`、`get all`（`take`） | 撿起地上的東西；太遠會自己走過去。也可以直接點擊地上的布袋 |
| `drop <物品>` | 丟下整格物品（裝備中的要先卸下） |
| `wear <物品>`（`wield`） | 穿戴裝備，同欄位的舊裝備會自動換下 |
| `remove <物品>` | 卸下裝備 |
| `use <物品>`（`eat`、`drink`） | 使用消耗品 |
| `list`（`shop`） | 查看附近商人賣什麼；也可以直接點擊商人 |
| `buy <物品> [數量]` | 向商人購買，例如 `buy herb 3`（數量 1～99） |
| `sell <物品> [數量\|all]` | 把東西賣給商人，例如 `sell 兔毛 all` |
| `value <物品>` | 請附近商人估價 |
| `go <出口>`（`enter`） | 前往其他區域；也可以直接打出口的方向詞，例如 `north`、`n`，或點擊出口的光柱 |
| `help` | 列出所有指令 |

指令中的 `<物品>` 與 `<目標>` 可以是中文名稱、英文代稱或 `#編號`，例如 `wear dagger`、`eat 兔肉`、`drop #3`。
背包面板（按 `I` 開關）上的按鈕送出的也是這些指令。點擊地面移動也會停止攻擊。新增指令：在 `server/.../world/Commands.java` 的建構子裡 `register`。

## 戰鬥規則

回合制近戰，沿用 MUD 的 tick 精神：

- 每個在戰鬥中的角色**每秒出手一次**；不在攻擊距離（1.8 公尺）內時會先追上目標。
- 命中率 80%，每差一級 ±5%（限制在 30%～95%）。
- 傷害 = 攻擊力 × (0.7～1.3) − 防禦力 ÷ 2，至少 1 點。
- 被攻擊的 NPC 會反擊；`aggressive` 的 NPC 會主動攻擊 6 公尺內的玩家。
- NPC 離出生點超過 18 公尺就放棄追擊，走回家後補滿 HP。
- 脫離戰鬥 5 秒後開始回血，每 3 秒回 5%。
- 擊殺經驗依等級差調整：NPC 每高一級 +20%、每低一級 −20%（10%～200%）。
- 玩家死亡：扣本級 10% 經驗（不降級），在區域出生點滿血復活。
- 角色每 60 秒自動存檔，離線與關機時也會存檔。

## 物品與掉寶

- NPC 死亡時依掉寶表擲骰，每一列各自獨立判定。掉落物散在屍體周圍。
- 前 30 秒只有擊殺者能撿，之後任何人都能撿；2 分鐘後消失。自己丟下的東西任何人都能撿。
- 背包 20 格，裝備中的物品也佔格。材料與消耗品同種會疊在一起（預設每格 20 個）。
- 裝備分四個欄位：武器、頭部、身體、腳部。數值 = 等級基礎值 + 裝備加成。
- 消耗品使用後有 2 秒冷卻；滿血時不能使用，避免浪費。
- 新角色出生時帶著 `starting-items` 指定的物品，裝備類會自動穿上。

## 金錢與商店

- 貨幣是銅錢。擊殺 NPC 時依 `gold-min`～`gold-max` 直接搜出銅錢（不會掉在地上）。新角色出生帶 `starting-gold` 枚。
- 商人是不能攻擊的 NPC（模板設定 `shop`）。點擊商人或輸入 `list` 會走過去並打開商店；交易時必須在商人 4 公尺內，走遠了商店會自動關閉。
- 售價預設為物品的 `value`，商店可以用 `price` 覆寫；收購價 = `value` × 商店的 `buy-rate`（至少 1 枚）。
- 商人只收 `buys` 列出的物品類型；新手村的陳老闆只收材料（`misc`），裝備中的物品不能賣。
- 商店面板上的按鈕送出的也是 `buy`、`sell` 指令。

數值公式在 `Combat.java`、`PlayerEntity.java`（等級成長）、`Zone.java`（經驗、回血）。

## 區域與傳送

目前有兩個區域：

| 區域 | 大小 | 內容 |
|---|---|---|
| 新手村 | 60 × 60 | 雜貨店陳老闆、老母雞、野兔、村外的野狼。北邊小徑通往黑松林 |
| 黑松林 | 80 × 80 | 獵人老李（收購材料與裝備、販賣獵人裝備）、野狼、毒蜘蛛、山豬，深處有黑熊。南邊小徑回新手村 |

- 每個區域各自一條 tick 執行緒。換區時，原區域把玩家移除，把記憶體中的狀態（等級、生命、銅錢、背包）交給目標區域，並立刻存檔，存檔位置是目的地。
- 必須站在出口 2.5 公尺內才能通過，太遠會自己走過去。戰鬥中（自己在打或被追打）不能離開。
- 在某個區域死亡，會在該區域的 `spawn` 復活。
- 同一角色重複登入時，會被送進他目前所在的區域，沿用記憶體中的狀態，並踢掉舊連線。
- 換區途中斷線時，Leave 事件可能送到舊區域；每個區域每個 tick 都會清掉連線已關閉的玩家，所以不會留下殘影。

## 遊戲內容

區域與 NPC 定義在 `server/src/main/resources/world/content.yml`，與程式設定分開。新增 NPC：

1. 在 `npc-templates` 加一個模板（等級、生命、攻防、經驗、速度、是否主動攻擊、重生秒數……）。
2. 在區域的 `npcs` 放置它：`{ template: wolf, x: 22, z: 20, count: 2 }`。
3. 前端外觀在 `client/src/game/models.ts`，以模板 id 對應；沒有對應的模型會顯示成紫色方塊。

新增物品：在 `item-templates` 加一個模板（`type` 為 `equipment`、`consumable`、`misc`），再放進 NPC 的 `loot` 掉寶表：`{ item: wolf-pelt, chance: 0.5, min: 1, max: 2 }`。
地上的物品在前端統一顯示為布袋，顏色依物品 id 決定。

新增區域：在 `zones` 加一個區域，並在兩邊的 `exits` 互相連接：`{ name: 往黑松林的小徑, keywords: [north, n], x: 0, z: -27, to: dark-pine-forest, to-x: 0, to-z: 30 }`。
抵達點建議離對面的出口 3 公尺以上，避免一抵達就站在出口上。前端的地形與氣氛在 `client/src/game/zones.ts`，依區域 id 對應；沒有對應的區域會用新手村的配色、沒有擺設。

新增商店：在 `shops` 加一間店（`buy-rate`、`buys`、`sells`），再建立一個 `shop: <商店 id>` 的 NPC 模板並放進區域。

啟動時會檢查所有引用（區域 → NPC 模板、出口 → 目的地區域、掉寶表／出生物品／商店貨品 → 物品模板、商人 → 商店）是否存在，出口與抵達點是否在區域範圍內，以及貨品是否有售價；有問題就無法啟動。
已經存進資料庫、但從內容檔刪掉的物品，玩家下次進場時會被略過，並寫入警告日誌。

## 設定

`server/src/main/resources/application.yml`：

- `theages.jwt.secret`：**正式環境必須用環境變數 `THEAGES_JWT_SECRET` 覆寫**（Base64，至少 32 bytes）。
- `theages.websocket.allowed-origins`：允許連線的前端網址。
- `theages.world.tick-rate`：每秒 tick 次數（戰鬥回合固定為 1 秒，不受影響）。

## 已知限制（之後處理）

- 玩家斷線後立刻重連，可能讀到尚未寫入的舊位置（存檔是非同步的）。同一連線期間重複登入則不受影響，會沿用記憶體中的狀態。
- 指令與移動沒有頻率限制（rate limit）。
- 尚未有 PvP、組隊分經驗、技能、玩家之間的交易。
- 商店庫存無限，價格固定；買賣沒有記錄（之後若要查弊，需要交易日誌）。
- 物品沒有隨機屬性，同一種物品的數值都一樣。
- 角色名稱等於帳號名稱，一個帳號一個角色。
- 場景是程式產生的佔位模型，之後換成 glTF。
- Redis 已放在 `docker-compose.yml`，但伺服器還沒使用。
