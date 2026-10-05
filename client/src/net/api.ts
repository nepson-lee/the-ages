export class ApiError extends Error {}

async function postAuth(path: string, username: string, password: string): Promise<string> {
  const res = await fetch(path, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, password }),
  });
  const body = await res.json().catch(() => ({}));
  if (!res.ok) {
    // 驗證錯誤（400）由 Spring 回傳 detail；自訂錯誤回傳 error
    throw new ApiError(body.error ?? body.detail ?? `伺服器錯誤（${res.status}）`);
  }
  return body.token as string;
}

export const login = (username: string, password: string) => postAuth("/api/auth/login", username, password);
export const register = (username: string, password: string) => postAuth("/api/auth/register", username, password);
