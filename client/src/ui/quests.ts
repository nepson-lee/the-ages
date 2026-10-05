import { QuestOfferStatus, type QuestDialog, type QuestLog } from "../gen/theages/v1/game_pb";

/**
 * 任務對話面板：和 NPC 說話時出現。按鈕送出文字指令
 * （`quest accept <id>`、`quest complete <id>`、`quest abandon <id>`），判定一律在伺服器。
 */
export class QuestDialogPanel {
  readonly element: HTMLElement;
  private readonly title: HTMLElement;
  private readonly greeting: HTMLElement;
  private readonly offers: HTMLElement;

  constructor(private readonly send: (command: string) => void) {
    this.element = document.createElement("section");
    this.element.className = "quest-dialog hidden";
    this.element.setAttribute("aria-label", "任務");
    this.element.innerHTML = `
      <header><h2 class="title"></h2><button class="close" aria-label="關閉">×</button></header>
      <p class="greeting"></p>
      <ul class="offers"></ul>`;
    this.title = this.element.querySelector(".title")!;
    this.greeting = this.element.querySelector(".greeting")!;
    this.offers = this.element.querySelector(".offers")!;
    this.element.querySelector(".close")!.addEventListener("click", () => this.close());
    this.element.addEventListener("click", (ev) => {
      const button = (ev.target as HTMLElement).closest<HTMLButtonElement>("button[data-command]");
      if (button) {
        this.send(button.dataset.command!);
      }
    });
  }

  show(dialog: QuestDialog): void {
    this.title.textContent = dialog.npcName;
    this.greeting.textContent = dialog.greeting;
    this.element.classList.remove("hidden");
    if (dialog.offers.length === 0) {
      this.offers.replaceChildren(Object.assign(document.createElement("li"), {
        className: "empty",
        textContent: "目前沒有可以接的任務。",
      }));
      return;
    }
    this.offers.replaceChildren(...dialog.offers.map((offer) => {
      const li = document.createElement("li");
      li.className = `status-${QuestOfferStatus[offer.status].toLowerCase()}`;
      const name = document.createElement("h3");
      name.textContent = offer.name + (offer.status === QuestOfferStatus.READY ? "（可以回報）" : offer.status === QuestOfferStatus.IN_PROGRESS ? "（進行中）" : "");
      const desc = Object.assign(document.createElement("p"), { textContent: offer.description });
      const meta = Object.assign(document.createElement("p"), {
        className: "meta",
        textContent: `目標：${offer.objectives}　獎勵：${offer.rewards}`,
      });
      const actions = document.createElement("div");
      actions.className = "actions";
      if (offer.status === QuestOfferStatus.AVAILABLE) {
        actions.append(button("接受", `quest accept ${offer.id}`));
      } else if (offer.status === QuestOfferStatus.READY) {
        actions.append(button("完成任務", `quest complete ${offer.id}`));
      } else {
        actions.append(button("放棄", `quest abandon ${offer.id}`, "danger"));
      }
      li.append(name, desc, meta, actions);
      return li;
    }));
  }

  close(): void {
    this.element.classList.add("hidden");
  }
}

/** 左側的任務追蹤：進行中任務的目標進度。沒有任務時隱藏。 */
export class QuestTracker {
  readonly element: HTMLElement;
  private readonly list: HTMLElement;

  constructor() {
    this.element = document.createElement("section");
    this.element.className = "quest-tracker hidden";
    this.element.setAttribute("aria-label", "任務追蹤");
    this.element.innerHTML = `<h2>任務</h2><ul></ul>`;
    this.list = this.element.querySelector("ul")!;
  }

  update(log: QuestLog): void {
    this.element.classList.toggle("hidden", log.quests.length === 0);
    this.list.replaceChildren(...log.quests.map((q) => {
      const li = document.createElement("li");
      li.className = q.ready ? "ready" : "";
      li.title = `${q.description}\n獎勵：${q.rewards}`;
      const name = Object.assign(document.createElement("div"), { className: "name", textContent: q.name });
      li.append(name);
      for (const o of q.objectives) {
        li.append(Object.assign(document.createElement("div"), {
          className: `objective${o.current >= o.required ? " done" : ""}`,
          textContent: `${o.text} ${o.current}/${o.required}`,
        }));
      }
      if (q.ready || q.objectives.length === 0) {
        li.append(Object.assign(document.createElement("div"), {
          className: "objective turn-in",
          textContent: `回報給${q.turnInName}`,
        }));
      }
      return li;
    }));
  }
}

function button(label: string, command: string, extraClass = ""): HTMLButtonElement {
  const b = Object.assign(document.createElement("button"), { type: "button", textContent: label, className: extraClass });
  b.dataset.command = command;
  return b;
}
