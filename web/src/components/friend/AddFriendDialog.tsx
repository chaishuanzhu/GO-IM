import { useState, type FormEvent } from 'react';
import { Search, X } from 'lucide-react';
import { searchUsers, sendFriendRequest } from '@/lib/api';
import { useAuthStore } from '@/stores/authStore';
import { useFriendStore } from '@/stores/friendStore';
import { useProfileStore } from '@/stores/profileStore';
import { wsManager } from '@/lib/ws';
import { Cmd, ChatType, MsgType } from '@/types';
import type { UserProfile } from '@/types';
import UserAvatar from '@/components/common/UserAvatar';

interface AddFriendDialogProps {
  open: boolean;
  onClose: () => void;
}

/** 添加好友：按昵称（包含）、邮箱或用户 ID（精确）搜索后发送请求。 */
export default function AddFriendDialog({ open, onClose }: AddFriendDialogProps) {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<UserProfile[]>([]);
  const [searched, setSearched] = useState(false);
  const [searching, setSearching] = useState(false);
  const [sendingUid, setSendingUid] = useState<string | null>(null);
  const [requested, setRequested] = useState<Set<string>>(new Set());
  const [error, setError] = useState('');
  const myUid = useAuthStore((s) => s.uid);
  const friends = useFriendStore((s) => s.friends);

  if (!open) return null;

  const close = () => {
    setQuery('');
    setResults([]);
    setSearched(false);
    setError('');
    setRequested(new Set());
    onClose();
  };

  const handleSearch = async (e: FormEvent) => {
    e.preventDefault();
    const q = query.trim();
    if (!q) return;
    setSearching(true);
    setError('');
    try {
      const res = await searchUsers(q);
      const users = res.users ?? [];
      setResults(users);
      useProfileStore.getState().put(users);
    } catch (err) {
      setResults([]);
      setError(err instanceof Error ? err.message : '搜索失败');
    } finally {
      setSearched(true);
      setSearching(false);
    }
  };

  const handleAdd = async (target: string) => {
    setSendingUid(target);
    setError('');
    try {
      await sendFriendRequest(target);
      // 通过 WebSocket 实时通知。
      wsManager.send({
        seq: '0',
        msgId: '0',
        cmd: Cmd.FriendRequest,
        from: myUid,
        to: target,
        chatType: ChatType.Single,
        msgType: MsgType.Text,
        content: '',
        timestamp: String(Date.now()),
        needAck: false,
      });
      setRequested((s) => new Set(s).add(target));
    } catch (err) {
      setError(err instanceof Error ? err.message : '发送失败');
    } finally {
      setSendingUid(null);
    }
  };

  const statusLabel = (u: UserProfile): string | null => {
    if (u.uid === myUid) return '自己';
    if (friends.some((f) => f.uid === u.uid)) return '已是好友';
    if (requested.has(u.uid)) return '已发送';
    return null;
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
      <div className="bg-white dark:bg-gray-900 rounded-xl shadow-xl w-full max-w-sm mx-4 p-6 space-y-4">
        <div className="flex items-center justify-between">
          <h3 className="text-lg font-semibold text-gray-900 dark:text-gray-100">添加好友</h3>
          <button onClick={close} className="p-1 rounded-lg hover:bg-gray-100 dark:hover:bg-gray-800">
            <X className="w-5 h-5 text-gray-500" />
          </button>
        </div>

        <form onSubmit={handleSearch} className="flex gap-2">
          <input
            type="text"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="昵称 / 邮箱 / 用户 ID"
            autoFocus
            className="flex-1 min-w-0 px-4 py-2.5 rounded-lg border border-gray-300 dark:border-gray-700 bg-white dark:bg-gray-800 text-gray-900 dark:text-gray-100 focus:ring-2 focus:ring-primary-500 focus:border-primary-500 outline-none text-sm"
          />
          <button
            type="submit"
            disabled={searching || !query.trim()}
            className="px-3 bg-primary-600 text-white rounded-lg hover:bg-primary-700 disabled:opacity-50 transition-colors"
            title="搜索"
          >
            <Search className="w-4 h-4" />
          </button>
        </form>

        {error && (
          <div className="p-2 rounded-lg text-xs bg-red-50 dark:bg-red-900/20 text-red-600 dark:text-red-400">{error}</div>
        )}

        <div className="max-h-80 overflow-y-auto -mx-2">
          {searching && <p className="px-2 py-3 text-sm text-gray-400">搜索中...</p>}
          {!searching && searched && results.length === 0 && !error && (
            <p className="px-2 py-3 text-sm text-gray-400">没有找到用户</p>
          )}
          {!searching &&
            results.map((u) => {
              const label = statusLabel(u);
              return (
                <div key={u.uid} className="flex items-center gap-3 px-2 py-2 rounded-lg hover:bg-gray-50 dark:hover:bg-gray-800">
                  <UserAvatar uid={u.uid} name={u.nickname} avatarUrl={u.avatar_url} className="w-10 h-10 text-sm" />
                  <div className="flex-1 min-w-0">
                    <p className="text-sm font-medium text-gray-900 dark:text-gray-100 truncate">{u.nickname || u.uid}</p>
                    <p className="text-xs text-gray-500 dark:text-gray-400 truncate">ID：{u.uid}</p>
                  </div>
                  {label ? (
                    <span className="text-xs text-gray-400">{label}</span>
                  ) : (
                    <button
                      onClick={() => handleAdd(u.uid)}
                      disabled={sendingUid !== null}
                      className="px-3 py-1.5 text-xs bg-primary-600 text-white rounded-lg hover:bg-primary-700 disabled:opacity-50 transition-colors"
                    >
                      {sendingUid === u.uid ? '发送中...' : '添加'}
                    </button>
                  )}
                </div>
              );
            })}
        </div>
      </div>
    </div>
  );
}
