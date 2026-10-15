/**
 * 认证辅助函数:token 存储与 JWT 工具。
 */

const TOKEN_KEY = 'im_token';
const UID_KEY = 'im_uid';
const PROFILE_KEY = 'im_profile';
/** 旧版本只存了昵称（当时叫 username）。 */
const LEGACY_USERNAME_KEY = 'im_username';

export interface StoredProfile {
  nickname: string;
  email: string;
  avatarUrl: string;
}

export function getToken(): string | null {
  return sessionStorage.getItem(TOKEN_KEY);
}

export function getStoredUid(): string | null {
  return sessionStorage.getItem(UID_KEY);
}

export function getStoredProfile(): StoredProfile {
  const empty: StoredProfile = { nickname: '', email: '', avatarUrl: '' };
  const raw = sessionStorage.getItem(PROFILE_KEY);
  if (raw) {
    try {
      return { ...empty, ...JSON.parse(raw) };
    } catch {
      // 损坏的数据按未保存处理
    }
  }
  return { ...empty, nickname: sessionStorage.getItem(LEGACY_USERNAME_KEY) || '' };
}

export function saveProfile(profile: StoredProfile): void {
  sessionStorage.setItem(PROFILE_KEY, JSON.stringify(profile));
}

export function saveAuth(uid: string, token: string, profile: StoredProfile): void {
  sessionStorage.setItem(TOKEN_KEY, token);
  sessionStorage.setItem(UID_KEY, uid);
  sessionStorage.removeItem(LEGACY_USERNAME_KEY);
  saveProfile(profile);
}

export function clearAuth(): void {
  sessionStorage.removeItem(TOKEN_KEY);
  sessionStorage.removeItem(UID_KEY);
  sessionStorage.removeItem(PROFILE_KEY);
  sessionStorage.removeItem(LEGACY_USERNAME_KEY);
}

/** 检查存储的 JWT 是否已过期(简单的客户端检查) */
export function isTokenExpired(): boolean {
  const token = getToken();
  if (!token) return true;

  try {
    // JWT 载荷是第二个分段 (base64url)
    // JWT 使用 base64url(非标准 base64):将 - → +、_ → /
    const base64 = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
    const payload = JSON.parse(atob(base64));
    const exp = payload.exp * 1000; // JWT exp 单位为秒
    return Date.now() >= exp;
  } catch {
    return true; // 无法解析的 token → 视为已过期
  }
}
