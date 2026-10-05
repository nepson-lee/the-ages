import type { PartyView } from "../gen/theages/v1/game_pb";

/** 左側的隊伍面板：隊員的等級、生命與所在區域。不在隊伍中時隱藏。 */
export class PartyPanel {
  readonly element: HTMLElement;
  private readonly list: HTMLElement;
  private zoneName = "";

  constructor() {
    this.element = document.createElement("section");
    this.element.className = "party hidden";
    this.element.setAttribute("aria-label", "隊伍");
    this.element.innerHTML = `<h2>隊伍</h2><ul></ul>`;
    this.list = this.element.querySelector("ul")!;
  }

  /** 自己目前所在的區域；隊員在別的區域時才顯示區域名稱。 */
  setZone(name: string): void {
    this.zoneName = name;
  }

  update(view: PartyView): void {
    this.element.classList.toggle("hidden", view.members.length === 0);
    this.list.replaceChildren(...view.members.map((m) => {
      const li = document.createElement("li");
      li.className = m.self ? "self" : "";
      const ratio = m.maxHp > 0 ? m.hp / m.maxHp : 0;
      li.innerHTML = `
        <div class="row"><span class="name"></span><span class="level"></span></div>
        <div class="hp"><div class="fill"></div></div>
        <div class="where"></div>`;
      li.querySelector(".name")!.textContent = (m.leader ? "★ " : "") + m.name;
      li.querySelector(".level")!.textContent = m.level ? `Lv${m.level}` : "";
      li.querySelector<HTMLElement>(".fill")!.style.width = `${Math.round(ratio * 100)}%`;
      li.querySelector<HTMLElement>(".fill")!.classList.toggle("low", ratio < 0.3);
      li.querySelector(".where")!.textContent = m.zoneName && m.zoneName !== this.zoneName ? m.zoneName : "";
      return li;
    }));
  }
}
