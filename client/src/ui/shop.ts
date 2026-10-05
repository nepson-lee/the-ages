import type { Inventory, ShopOffer, ShopView } from "../gen/theages/v1/game_pb";

/**
 * 商店面板。和背包面板一樣，按鈕只送出文字指令（`buy <id> <數量>`、`sell #<uid> <數量|all>`），
 * 價格與能否交易一律由伺服器決定。
 */
export class ShopPanel {
  readonly element: HTMLElement;
  private readonly title: HTMLElement;
  private readonly gold: HTMLElement;
  private readonly offers: HTMLElement;
  private readonly quotes: HTMLElement;
  private view: ShopView | null = null;
  private inventory: Inventory | null = null;
  private goldAmount = 0;

  constructor(private readonly send: (command: string) => void) {
    this.element = document.createElement("section");
    this.element.className = "shop hidden";
    this.element.setAttribute("aria-label", "商店");
    this.element.innerHTML = `
      <header><h2 class="title"></h2><button class="close" aria-label="關閉">×</button></header>
      <p class="gold"></p>
      <h3>販賣</h3>
      <ul class="offers"></ul>
      <h3>收購</h3>
      <ul class="quotes"></ul>`;
    this.title = this.element.querySelector(".title")!;
    this.gold = this.element.querySelector(".gold")!;
    this.offers = this.element.querySelector(".offers")!;
    this.quotes = this.element.querySelector(".quotes")!;
    this.element.querySelector(".close")!.addEventListener("click", () => this.close());
    this.element.addEventListener("click", (ev) => {
      const button = (ev.target as HTMLElement).closest<HTMLButtonElement>("button[data-command]");
      if (button) {
        this.send(button.dataset.command!);
      }
    });
  }

  get isOpen(): boolean {
    return !this.element.classList.contains("hidden");
  }

  show(view: ShopView): void {
    this.view = view;
    this.element.classList.remove("hidden");
    this.render();
  }

  close(): void {
    this.view = null;
    this.element.classList.add("hidden");
  }

  setInventory(inventory: Inventory): void {
    this.inventory = inventory;
  }

  setGold(gold: number): void {
    this.goldAmount = gold;
    if (this.view) {
      this.renderGold();
    }
  }

  private render(): void {
    const view = this.view!;
    this.title.textContent = `${view.shopName}・${view.merchantName}`;
    this.renderGold();

    this.offers.replaceChildren(...view.offers.map((offer) => {
      const li = document.createElement("li");
      li.title = offer.description;
      li.append(
        span("item-name", offer.name),
        span("item-stats", summary(offer)),
        span("price", `${offer.price} 文`),
        actions(button("買 1", `buy ${offer.templateId} 1`), button("買 5", `buy ${offer.templateId} 5`)),
      );
      return li;
    }));

    const items = new Map((this.inventory?.items ?? []).map((i) => [i.uid, i]));
    const rows = view.quotes.flatMap((quote) => {
      const item = items.get(quote.uid);
      if (!item) {
        return [];
      }
      const li = document.createElement("li");
      li.append(
        span("item-name", item.quantity > 1 ? `${item.name} ×${item.quantity}` : item.name),
        span("item-stats", item.quantity > 1 ? `全部 ${quote.price * item.quantity} 文` : ""),
        span("price", `${quote.price} 文`),
        actions(
          button("賣 1", `sell #${quote.uid} 1`),
          ...(item.quantity > 1 ? [button("全賣", `sell #${quote.uid} all`)] : []),
        ),
      );
      return [li];
    });
    if (rows.length === 0) {
      rows.push(Object.assign(document.createElement("li"), { className: "empty", textContent: "身上沒有老闆想收的東西。" }));
    }
    this.quotes.replaceChildren(...rows);
  }

  private renderGold(): void {
    this.gold.textContent = `你有 ${this.goldAmount} 枚銅錢`;
  }
}

function span(className: string, text: string): HTMLSpanElement {
  return Object.assign(document.createElement("span"), { className, textContent: text });
}

function button(label: string, command: string): HTMLButtonElement {
  const b = Object.assign(document.createElement("button"), { type: "button", textContent: label });
  b.dataset.command = command;
  return b;
}

function actions(...buttons: HTMLButtonElement[]): HTMLSpanElement {
  const s = span("actions", "");
  s.append(...buttons);
  return s;
}

function summary(o: ShopOffer): string {
  const parts: string[] = [];
  if (o.attack) parts.push(`攻 +${o.attack}`);
  if (o.defense) parts.push(`防 +${o.defense}`);
  if (o.maxHp) parts.push(`命 +${o.maxHp}`);
  if (o.heal) parts.push(`回復 ${o.heal}`);
  return parts.join(" ");
}
