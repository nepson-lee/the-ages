import "./style.css";
import type { ServerMessage } from "./gen/theages/v1/game_pb";
import { WorldView } from "./game/world-view";
import { GameConnection } from "./net/connection";
import { GameConsole } from "./ui/console";
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
  root.innerHTML = `<div class="game"><div class="viewport"></div><div class="hud"><span class="zone"></span></div></div>`;
  const zoneLabel = root.querySelector<HTMLElement>(".zone")!;

  let connection: GameConnection | null = null;
  const view = new WorldView(root.querySelector(".viewport")!, (x, z) => connection?.moveTo(x, z));
  const gameConsole = new GameConsole((text) => connection?.command(text));
  root.querySelector(".game")!.append(gameConsole.element);
  gameConsole.print("正在連線到時空之門……");

  connection = new GameConnection(token, {
    onMessage: (msg: ServerMessage) => {
      const p = msg.payload;
      switch (p.case) {
        case "welcome":
          view.enterZone(p.value.selfId, p.value.zoneSize);
          zoneLabel.textContent = p.value.zoneName;
          gameConsole.print("點擊地面移動；按 Enter 輸入指令。");
          break;
        case "snapshot":
          p.value.entities.forEach((e) => view.upsert(e));
          break;
        case "entityLeft":
          view.remove(p.value.id);
          break;
        case "text":
          gameConsole.print(p.value.text, p.value.channel);
          break;
      }
    },
    onClose: (reason) => {
      activeConsole = null;
      view.dispose();
      storage()?.removeItem(TOKEN_KEY);
      showLogin(root, start, reason);
    },
  });

  activeConsole = gameConsole;
}

let activeConsole: GameConsole | null = null;
window.addEventListener("keydown", (ev) => {
  if (ev.key === "Enter") {
    activeConsole?.focus();
  }
});

const saved = storage()?.getItem(TOKEN_KEY);
if (saved) {
  start(saved);
} else {
  showLogin(root, start);
}
