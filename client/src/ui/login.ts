import { ApiError, login, register } from "../net/api";

/** 顯示登入畫面，成功後以 JWT 呼叫 onToken。 */
export function showLogin(root: HTMLElement, onToken: (token: string) => void, notice?: string): void {
  root.innerHTML = `
    <form class="login">
      <h1>時空之門</h1>
      <p class="subtitle">The Ages</p>
      <input name="username" placeholder="名字（英數字）" autocomplete="username" required />
      <input name="password" type="password" placeholder="密碼（至少 8 碼）" autocomplete="current-password" required />
      <div class="buttons">
        <button type="submit" data-action="login">進入遊戲</button>
        <button type="submit" data-action="register" class="secondary">建立新角色</button>
      </div>
      <p class="error" role="alert"></p>
    </form>`;

  const form = root.querySelector<HTMLFormElement>("form")!;
  const error = form.querySelector<HTMLParagraphElement>(".error")!;
  error.textContent = notice ?? "";

  form.addEventListener("submit", async (ev) => {
    ev.preventDefault();
    const action = (ev.submitter as HTMLButtonElement | null)?.dataset.action ?? "login";
    const data = new FormData(form);
    const username = String(data.get("username")).trim();
    const password = String(data.get("password"));
    const buttons = form.querySelectorAll("button");
    buttons.forEach((b) => (b.disabled = true));
    error.textContent = "";
    try {
      onToken(action === "register" ? await register(username, password) : await login(username, password));
    } catch (e) {
      error.textContent = e instanceof ApiError ? e.message : "無法連線到伺服器";
      buttons.forEach((b) => (b.disabled = false));
    }
  });
}
