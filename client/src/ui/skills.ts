import type { SkillBook, SkillInfo } from "../gen/theages/v1/game_pb";

const HOTBAR_SIZE = 6;

/**
 * 技能列（畫面右下角）：已學會的前 6 個技能，數字鍵 1～6 或點擊施放（送出 `cast <id>`）。
 * 冷卻時間由伺服器給「還要等多久」，這裡只負責倒數顯示；能不能施放仍由伺服器判定。
 */
export class Hotbar {
  readonly element: HTMLElement;
  private skills: SkillInfo[] = [];
  private readyAt = new Map<string, number>();
  private mp = 0;
  private readonly timer: number;

  constructor(private readonly send: (command: string) => void) {
    this.element = document.createElement("div");
    this.element.className = "hotbar hidden";
    this.element.setAttribute("aria-label", "技能列");
    this.element.addEventListener("click", (ev) => {
      const slot = (ev.target as HTMLElement).closest<HTMLButtonElement>("button[data-index]");
      if (slot) {
        this.cast(Number(slot.dataset.index));
      }
    });
    this.timer = window.setInterval(() => this.renderCooldowns(), 100);
  }

  update(book: SkillBook): void {
    const now = performance.now();
    this.skills = book.known.slice(0, HOTBAR_SIZE);
    this.readyAt = new Map(this.skills.map((s) => [s.id, now + s.remainingMs]));
    this.element.classList.toggle("hidden", this.skills.length === 0);
    this.element.replaceChildren(...this.skills.map((s, i) => {
      const b = document.createElement("button");
      b.type = "button";
      b.dataset.index = String(i);
      b.title = `${s.name}\n${s.description}`;
      b.innerHTML = `<span class="key">${i + 1}</span><span class="name"></span><span class="cost"></span><span class="cooldown"></span>`;
      b.querySelector(".name")!.textContent = s.name;
      b.querySelector(".cost")!.textContent = `${s.mpCost}`;
      return b;
    }));
    this.renderCooldowns();
  }

  setMp(mp: number): void {
    this.mp = mp;
    this.renderCooldowns();
  }

  /** 數字鍵 1～6。 */
  cast(index: number): void {
    const skill = this.skills[index];
    if (skill) {
      this.send(`cast ${skill.id}`);
    }
  }

  dispose(): void {
    window.clearInterval(this.timer);
  }

  private renderCooldowns(): void {
    const now = performance.now();
    this.element.querySelectorAll<HTMLButtonElement>("button[data-index]").forEach((b) => {
      const s = this.skills[Number(b.dataset.index)];
      const left = Math.max(0, (this.readyAt.get(s.id) ?? 0) - now);
      const overlay = b.querySelector<HTMLElement>(".cooldown")!;
      overlay.style.height = s.cooldownMs > 0 ? `${Math.min(100, (left / s.cooldownMs) * 100)}%` : "0%";
      overlay.textContent = left > 0 ? String(Math.ceil(left / 1000)) : "";
      b.classList.toggle("no-mp", this.mp < s.mpCost);
    });
  }
}

/** 技能面板（K）：已學會的技能，以及附近訓練師能教的技能（「學習」按鈕送出 `learn <id>`）。 */
export class SkillPanel {
  readonly element: HTMLElement;
  private readonly known: HTMLElement;
  private readonly trainerTitle: HTMLElement;
  private readonly learnable: HTMLElement;
  private lastTrainer = "";

  constructor(private readonly send: (command: string) => void) {
    this.element = document.createElement("section");
    this.element.className = "skill-panel hidden";
    this.element.setAttribute("aria-label", "技能");
    this.element.innerHTML = `
      <header><h2>技能</h2><button class="close" aria-label="關閉">×</button></header>
      <ul class="known"></ul>
      <h3 class="trainer"></h3>
      <ul class="learnable"></ul>
      <p class="hint">按 K 開關。找村長或獵人老李說話可以學新技能。</p>`;
    this.known = this.element.querySelector(".known")!;
    this.trainerTitle = this.element.querySelector(".trainer")!;
    this.learnable = this.element.querySelector(".learnable")!;
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

  update(book: SkillBook): void {
    this.known.replaceChildren(...(book.known.length ? book.known.map((s) => row(s)) : [empty("還沒學會任何技能。")]));
    this.trainerTitle.textContent = book.trainerName ? `${book.trainerName}可以教你` : "";
    this.learnable.replaceChildren(...book.learnable.map((s) => {
      const li = row(s);
      const actions = document.createElement("div");
      actions.className = "actions";
      if (s.canLearn) {
        const b = Object.assign(document.createElement("button"), { type: "button", textContent: `學習（${s.price} 文）` });
        b.dataset.command = `learn ${s.id}`;
        actions.append(b);
      } else {
        actions.append(Object.assign(document.createElement("span"), { className: "reason", textContent: s.reason }));
      }
      li.append(actions);
      return li;
    }));
    // 剛走到訓練師旁邊時自動打開一次，方便學技能；之後玩家關掉就不再彈出
    if (book.trainerName && book.trainerName !== this.lastTrainer && book.learnable.length > 0) {
      this.toggle(true);
    }
    this.lastTrainer = book.trainerName;
  }
}

function row(s: SkillInfo): HTMLLIElement {
  const li = document.createElement("li");
  li.append(
    Object.assign(document.createElement("div"), { className: "name", textContent: `${s.name}　Lv${s.level}` }),
    Object.assign(document.createElement("div"), { className: "desc", textContent: s.description }),
  );
  return li;
}

function empty(text: string): HTMLLIElement {
  return Object.assign(document.createElement("li"), { className: "empty", textContent: text });
}
