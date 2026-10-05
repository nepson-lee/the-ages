import type { SelfStats } from "../gen/theages/v1/game_pb";

/** 左上角狀態列：區域名稱、等級、生命、經驗。 */
export class Hud {
  readonly element: HTMLElement;
  private readonly zone: HTMLElement;
  private readonly level: HTMLElement;
  private readonly hpFill: HTMLElement;
  private readonly hpText: HTMLElement;
  private readonly expFill: HTMLElement;
  private readonly expText: HTMLElement;

  constructor() {
    this.element = document.createElement("div");
    this.element.className = "hud";
    this.element.innerHTML = `
      <div class="hud-title"><span class="zone"></span><span class="level"></span></div>
      <div class="bar hp"><div class="fill"></div><span class="text"></span></div>
      <div class="bar exp"><div class="fill"></div><span class="text"></span></div>`;
    const q = (s: string) => this.element.querySelector<HTMLElement>(s)!;
    this.zone = q(".zone");
    this.level = q(".level");
    this.hpFill = q(".hp .fill");
    this.hpText = q(".hp .text");
    this.expFill = q(".exp .fill");
    this.expText = q(".exp .text");
  }

  setZone(name: string): void {
    this.zone.textContent = name;
  }

  setStats(s: SelfStats): void {
    this.level.textContent = `Lv ${s.level}`;
    this.hpFill.style.width = `${pct(s.hp, s.maxHp)}%`;
    this.hpText.textContent = `生命 ${s.hp}/${s.maxHp}`;
    this.expFill.style.width = `${pct(s.exp, s.expToNext)}%`;
    this.expText.textContent = `經驗 ${s.exp}/${s.expToNext}`;
    this.element.classList.toggle("danger", s.hp > 0 && s.hp / s.maxHp < 0.3);
  }
}

const pct = (v: number, max: number) => (max > 0 ? Math.round((v / max) * 100) : 0);
