import { create } from 'zustand';
import { saveAuth, saveProfile, clearAuth, getStoredUid, getStoredProfile, getToken, isTokenExpired } from '@/lib/auth';
import type { LoginResponse, UserProfile } from '@/types';

/** 从 JWT 载荷中解码角色字段。 */
function getRoleFromToken(token: string): boolean {
  try {
    // JWT 使用 base64url(非标准 base64):将 - → +、_ → /
    const base64 = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
    const payload = JSON.parse(atob(base64));
    return payload.role === 'admin';
  } catch {
    return false;
  }
}

interface AuthState {
  uid: string;
  /** 显示用昵称，未知时为 uid */
  nickname: string;
  email: string;
  /** 相对网关的头像路径，未设置时为空串 */
  avatarUrl: string;
  token: string;
  isLoggedIn: boolean;
  isAdmin: boolean;

  login: (res: LoginResponse) => void;
  /** 资料修改或刷新后同步 */
  setProfile: (profile: UserProfile) => void;
  logout: () => void;
  restore: () => boolean;
}

export const useAuthStore = create<AuthState>((set, get) => ({
  uid: '',
  nickname: '',
  email: '',
  avatarUrl: '',
  token: '',
  isLoggedIn: false,
  isAdmin: false,

  login: (res) => {
    const profile = { nickname: res.nickname || res.uid, email: res.email ?? '', avatarUrl: res.avatar_url ?? '' };
    saveAuth(res.uid, res.token, profile);
    set({ uid: res.uid, token: res.token, ...profile, isLoggedIn: true, isAdmin: getRoleFromToken(res.token) });
  },

  setProfile: (p) => {
    const s = get();
    if (!s.isLoggedIn || p.uid !== s.uid) return;
    const profile = { nickname: p.nickname || s.uid, email: p.email ?? s.email, avatarUrl: p.avatar_url ?? '' };
    saveProfile(profile);
    set(profile);
  },

  logout: () => {
    clearAuth();
    set({ uid: '', nickname: '', email: '', avatarUrl: '', token: '', isLoggedIn: false, isAdmin: false });
  },

  restore: () => {
    const uid = getStoredUid();
    const token = getToken();
    if (uid && token && !isTokenExpired()) {
      const profile = getStoredProfile();
      set({
        uid,
        token,
        nickname: profile.nickname || uid,
        email: profile.email,
        avatarUrl: profile.avatarUrl,
        isLoggedIn: true,
        isAdmin: getRoleFromToken(token),
      });
      return true;
    }
    return false;
  },
}));
