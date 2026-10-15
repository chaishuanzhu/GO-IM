import { useEffect } from 'react';
import { create } from 'zustand';
import { getProfiles } from '@/lib/api';
import { useAuthStore } from '@/stores/authStore';
import type { UserProfile } from '@/types';

/** 用户资料缓存：昵称 + 头像，按 UID 批量拉取。 */

const TTL_MS = 10 * 60 * 1000;
/** 拉取失败（或用户不存在）后多久再试 */
const RETRY_MS = 60 * 1000;
const BATCH_WINDOW_MS = 50;
const BATCH_SIZE = 200;

interface CachedProfile extends UserProfile {
  fetchedAt: number;
}

interface ProfileState {
  profiles: Record<string, CachedProfile>;
  /** 写入已知资料（好友列表、搜索结果、自己修改后等） */
  put: (list: UserProfile[]) => void;
  /** 缓存缺失或过期时排队拉取 */
  request: (uids: string[]) => void;
  clear: () => void;
}

const pending = new Set<string>();
const lastTried = new Map<string, number>();
let timer: ReturnType<typeof setTimeout> | null = null;

export const useProfileStore = create<ProfileState>((set, get) => ({
  profiles: {},

  put: (list) => {
    if (list.length === 0) return;
    const now = Date.now();
    set((s) => {
      const next = { ...s.profiles };
      for (const p of list) next[p.uid] = { ...next[p.uid], ...p, fetchedAt: now };
      return { profiles: next };
    });
  },

  request: (uids) => {
    const now = Date.now();
    const { profiles } = get();
    for (const uid of uids) {
      if (!uid || uid.startsWith('g_')) continue;
      const cached = profiles[uid];
      if (cached && now - cached.fetchedAt < TTL_MS) continue;
      if (now - (lastTried.get(uid) ?? 0) < RETRY_MS) continue;
      pending.add(uid);
    }
    if (pending.size > 0 && timer === null) {
      timer = setTimeout(flush, BATCH_WINDOW_MS);
    }
  },

  clear: () => {
    pending.clear();
    lastTried.clear();
    set({ profiles: {} });
  },
}));

// 换账号或退出登录时丢弃上一个账号的缓存
useAuthStore.subscribe((s, prev) => {
  if (s.uid !== prev.uid) useProfileStore.getState().clear();
});

async function flush() {
  timer = null;
  if (!useAuthStore.getState().isLoggedIn) {
    pending.clear();
    return;
  }
  const uids = [...pending];
  pending.clear();
  const now = Date.now();
  uids.forEach((uid) => lastTried.set(uid, now));
  for (let i = 0; i < uids.length; i += BATCH_SIZE) {
    try {
      const res = await getProfiles(uids.slice(i, i + BATCH_SIZE));
      useProfileStore.getState().put(res.users ?? []);
    } catch {
      // 资料只用于展示，失败时继续显示 UID，RETRY_MS 后再试
    }
  }
}

/** 订阅某个用户的资料；自己用登录态里的资料。 */
export function useProfile(uid: string | undefined): UserProfile | undefined {
  const self = useAuthStore((s) => (s.uid === uid ? s : null));
  const cached = useProfileStore((s) => (uid ? s.profiles[uid] : undefined));
  const request = useProfileStore((s) => s.request);
  const isSelf = self !== null;
  useEffect(() => {
    if (uid && !isSelf) request([uid]);
  }, [uid, isSelf, request]);
  if (self) return { uid: self.uid, nickname: self.nickname, avatar_url: self.avatarUrl, email: self.email };
  return cached;
}

/** 显示名：昵称优先，未知时用 [fallback]，再退回 UID。 */
export function useDisplayName(uid: string, fallback?: string): string {
  const profile = useProfile(uid);
  return profile?.nickname?.trim() || fallback?.trim() || uid;
}

/** 非组件代码（预览文本等）读取缓存中的昵称。 */
export function cachedNickname(uid: string): string | undefined {
  const auth = useAuthStore.getState();
  if (auth.uid === uid) return auth.nickname;
  return useProfileStore.getState().profiles[uid]?.nickname || undefined;
}
