import { EquipSlot, ItemType, type Inventory, type InventoryItem } from "../gen/theages/v1/game_pb";

const SLOTS: [EquipSlot, string][] = [
  [EquipSlot.WEAPON, "武器"],
  [EquipSlot.HEAD, "頭部"],
  [EquipSlot.BODY, "身體"],
  [EquipSlot.FEET, "腳部"],
];

/**
 * 背包與裝備面板。按鈕只是送出文字指令（例如 `wear #3`），
 * 與玩家自己打指令走同一條路徑，所有判定都在伺服器。
 */
export class InventoryPanel {
  readonly element: HTMLElement;
  private readonly slots: HTMLElement;
  private readonly list: HTMLElement;
  private readonly count: HTMLElement;

  constructor(private readonly send: (command: string) => void) {
    this.element = document.createElement("section");
    this.element.className = "inventory hidden";
    this.element.setAttribute("aria-label", "背包");
    this.element.innerHTML = `
      <header><h2>背包 <span class="count"></span></h2><button class="close" aria-label="關閉">×</button></header>
      <div class="slots"></div>
      <ul class="items"></ul>
      <p class="hint">按 I 開關背包。點擊地上的布袋可以撿起物品。</p>`;
    this.slots = this.element.querySelector(".slots")!;
    this.list = this.element.querySelector(".items")!;
    this.count = this.element.querySelector(".count")!;
    this.element.querySelector(".close")!.addEventListener("click", () => this.toggle(false));
    this.element.addEventListener("click", (ev) => {
      const button = (ev.target as HTMLElement).closest<HTMLButtonElement>("button[data-command]");
      if (button) {
        this.send(button.dataset.command!);
      }
    });
  }

  toggle(open = this.element.classList.contains("hidden")): void {
    this.element.classList.toggle("hidden", !open);
  }

  update(inv: Inventory): void {
    this.count.textContent = `${inv.items.length}/${inv.capacity}`;

    this.slots.replaceChildren(...SLOTS.map(([slot, label]) => {
      const item = inv.items.find((i) => i.equipped && i.slot === slot);
      const div = document.createElement("div");
      div.className = `slot${item ? " filled" : ""}`;
      div.innerHTML = `<span class="slot-label">${label}</span><span class="slot-item"></span>`;
      div.querySelector(".slot-item")!.textContent = item?.name ?? "—";
      if (item) {
        div.title = tooltip(item);
        div.append(commandButton("卸下", `remove #${item.uid}`));
      }
      return div;
    }));

    const bag = inv.items.filter((i) => !i.equipped);
    if (bag.length === 0) {
      const empty = document.createElement("li");
      empty.className = "empty";
      empty.textContent = "背包是空的。";
      this.list.replaceChildren(empty);
      return;
    }
    this.list.replaceChildren(...bag.map((item) => {
      const li = document.createElement("li");
      li.title = tooltip(item);
      const name = document.createElement("span");
      name.className = `item-name type-${ItemType[item.type].toLowerCase()}`;
      name.textContent = item.quantity > 1 ? `${item.name} ×${item.quantity}` : item.name;
      const stats = document.createElement("span");
      stats.className = "item-stats";
      stats.textContent = summary(item);
      const actions = document.createElement("span");
      actions.className = "actions";
      if (item.type === ItemType.EQUIPMENT) {
        actions.append(commandButton("裝備", `wear #${item.uid}`));
      } else if (item.type === ItemType.CONSUMABLE) {
        actions.append(commandButton("使用", `use #${item.uid}`));
      }
      actions.append(commandButton("丟棄", `drop #${item.uid}`, "danger"));
      li.append(name, stats, actions);
      return li;
    }));
  }
}

function commandButton(label: string, command: string, extraClass = ""): HTMLButtonElement {
  const b = document.createElement("button");
  b.type = "button";
  b.className = extraClass;
  b.textContent = label;
  b.dataset.command = command;
  return b;
}

function summary(item: InventoryItem): string {
  const parts: string[] = [];
  if (item.attack) parts.push(`攻 ${signed(item.attack)}`);
  if (item.defense) parts.push(`防 ${signed(item.defense)}`);
  if (item.maxHp) parts.push(`命 ${signed(item.maxHp)}`);
  if (item.heal) parts.push(`回復 ${item.heal}`);
  return parts.join(" ");
}

function tooltip(item: InventoryItem): string {
  const s = summary(item);
  return s ? `${item.description}\n${s}` : item.description;
}

const signed = (n: number) => (n > 0 ? `+${n}` : `${n}`);
