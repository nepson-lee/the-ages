import { TextChannel } from "../gen/theages/v1/game_pb";

const MAX_LINES = 300;

/** MUD 風格的文字輸出與指令列：Enter 送出，↑/↓ 叫回歷史指令。 */
export class GameConsole {
  readonly element: HTMLElement;
  private readonly log: HTMLElement;
  private readonly input: HTMLInputElement;
  private readonly history: string[] = [];
  private historyIndex = 0;

  constructor(onCommand: (text: string) => void) {
    this.element = document.createElement("div");
    this.element.className = "console";
    this.element.innerHTML = `<div class="log" aria-live="polite"></div>
      <input class="command" placeholder="輸入指令，例如 say 大家好、look、who、help" maxlength="256" />`;
    this.log = this.element.querySelector(".log")!;
    this.input = this.element.querySelector(".command")!;

    this.input.addEventListener("keydown", (ev) => {
      if (ev.key === "Enter") {
        const text = this.input.value.trim();
        if (text) {
          this.history.push(text);
          this.historyIndex = this.history.length;
          this.print(`> ${text}`, "echo");
          onCommand(text);
        }
        this.input.value = "";
      } else if (ev.key === "ArrowUp" && this.historyIndex > 0) {
        this.input.value = this.history[--this.historyIndex];
        ev.preventDefault();
      } else if (ev.key === "ArrowDown" && this.historyIndex < this.history.length) {
        this.input.value = this.history[++this.historyIndex] ?? "";
        ev.preventDefault();
      }
      ev.stopPropagation();
    });
  }

  print(text: string, kind: "echo" | TextChannel = TextChannel.SYSTEM): void {
    const line = document.createElement("div");
    line.className = `line ${typeof kind === "string" ? kind : CHANNEL_CLASS[kind]}`;
    line.textContent = text;
    this.log.append(line);
    while (this.log.childElementCount > MAX_LINES) {
      this.log.firstElementChild!.remove();
    }
    this.log.scrollTop = this.log.scrollHeight;
  }

  focus(): void {
    this.input.focus();
  }
}

const CHANNEL_CLASS: Record<TextChannel, string> = {
  [TextChannel.UNSPECIFIED]: "system",
  [TextChannel.SYSTEM]: "system",
  [TextChannel.SAY]: "say",
  [TextChannel.ROOM]: "room",
};
