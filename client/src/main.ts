import "./style.css";
import type { ServerMessage } from "./gen/theages/v1/game_pb";
import { WorldView } from "./game/world-view";
import { GameConnection } from "./net/connection";
import { GameConsole } from "./ui/console";
import { Hud } from "./ui/hud";
import { InventoryPanel } from "./ui/inventory";
import { PartyPanel } from "./ui/party";
import { ShopPanel } from "./ui/shop";
import { showLogin } from "./ui/login";

const TOKEN_KEY = "theages.token";
const root = document.querySelector<HTMLElement>("#app")!;

function storage(): Storage | null {
  try {
    return window.sessionStorage;
  } catch {
    return null;
  }
}

function start(token: string): void {
  storage()?.setItem(TOKEN_KEY, token);
  root.innerHTML = `<div class="game"><div class="viewport"></div></div>`;

  let connection: GameConnection | null = null;
  const view = new WorldView(root.querySelector(".viewport")!, {
    onGroundClick: (x, z) => connection?.moveTo(x, z),
    onEntityClick: (id) => connection?.attack(id),
    onItemClick: (id) => connection?.command(`get #${id}`),
    onMerchantClick: (id) => connection?.command(`list #${id}`),
    onPortalClick: (id) => connection?.command(`go #${id}`),
  });
  const hud = new Hud();
  const gameConsole = new GameConsole((text) => connection?.command(text));
  const inventory = new InventoryPanel((command) => connection?.command(command));
  hud.onInventoryClick(() => inventory.toggle());
  const shop = new ShopPanel((command) => connection?.command(command));
  const party = new PartyPanel();
  const leftColumn = document.createElement("div");
  leftColumn.className = "left-column";
  leftColumn.append(hud.element, party.element);
  root.querySelector(".game")!.append(leftColumn, gameConsole.element, inventory.element, shop.element);
  gameConsole.print("正在連線到時空之門……");

  connection = new GameConnection(token, {
    onMessage: (msg: ServerMessage) => {
      const p = msg.payload;
      switch (p.case) {
        case "welcome":
          view.enterZone(p.value.selfId, p.value.zoneId, p.value.zoneSize);
          shop.close();
          hud.setZone(p.value.zoneName);
          party.setZone(p.value.zoneName);
          gameConsole.print("點擊地面移動、點擊生物攻擊；按 Enter 輸入指令（help 查看全部，invite <名字> 組隊）。");
          break;
        case "snapshot":
          p.value.entities.forEach((e) => view.upsert(e));
          break;
        case "entityLeft":
          view.remove(p.value.id);
          break;
        case "combat":
          view.showCombat(p.value);
          break;
        case "selfStats":
          hud.setStats(p.value);
          shop.setGold(p.value.gold);
          break;
        case "inventory":
          inventory.update(p.value);
          shop.setInventory(p.value); // 伺服器接著會重送 ShopView，屆時重畫
          break;
        case "shop":
          shop.show(p.value);
          break;
        case "shopClosed":
          shop.close();
          break;
        case "party":
          party.update(p.value);
          break;
        case "text":
          gameConsole.print(p.value.text, p.value.channel);
          break;
      }
    },
    onClose: (reason) => {
      activeConsole = null;
      activeInventory = null;
      activeShop = null;
      view.dispose();
      storage()?.removeItem(TOKEN_KEY);
      showLogin(root, start, reason);
    },
  });

  activeConsole = gameConsole;
  activeInventory = inventory;
  activeShop = shop;
}

let activeConsole: GameConsole | null = null;
let activeInventory: InventoryPanel | null = null;
let activeShop: ShopPanel | null = null;
// 指令列有焦點時按鍵不會傳到這裡（console.ts 會 stopPropagation）
window.addEventListener("keydown", (ev) => {
  if (ev.key === "Enter") {
    activeConsole?.focus();
  } else if (ev.key === "i" || ev.key === "I") {
    activeInventory?.toggle();
  } else if (ev.key === "Escape") {
    activeInventory?.toggle(false);
    activeShop?.close();
  }
});

const saved = storage()?.getItem(TOKEN_KEY);
if (saved) {
  start(saved);
} else {
  showLogin(root, start);
}
