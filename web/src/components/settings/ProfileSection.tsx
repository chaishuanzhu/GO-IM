import { useEffect, useRef, useState } from 'react';
import { Camera, Check, Copy, Mail, Pencil, User, X } from 'lucide-react';
import { useAuthStore } from '@/stores/authStore';
import { ApiError, bindEmail, getMyProfile, sendBindEmailCode, updateProfile, uploadFile } from '@/lib/api';
import { useCountdown } from '@/hooks/useCountdown';
import { isValidEmail } from '@/lib/utils';
import UserAvatar from '@/components/common/UserAvatar';

const MAX_NICKNAME = 32;
const MAX_AVATAR_BYTES = 10 * 1024 * 1024;
const CODE_COOLDOWN = 60;

type Notice = { type: 'success' | 'error'; text: string } | null;

const inputClass =
  'w-full px-3 py-2 border border-gray-200 dark:border-gray-700 rounded-lg text-sm focus:outline-none focus:border-primary-400 focus:ring-1 focus:ring-primary-400 bg-white dark:bg-gray-800 text-gray-900 dark:text-gray-100';

function errorMessage(err: unknown, fallback: string): string {
  return err instanceof Error ? err.message : fallback;
}

/** 设置页「个人信息」：头像、昵称、用户 ID、邮箱绑定。 */
export default function ProfileSection() {
  const { uid, token, nickname, email, avatarUrl, setProfile } = useAuthStore();
  const [notice, setNotice] = useState<Notice>(null);
  const [uploading, setUploading] = useState(false);
  const [editingName, setEditingName] = useState(false);
  const [nameDraft, setNameDraft] = useState('');
  const [savingName, setSavingName] = useState(false);
  const fileInput = useRef<HTMLInputElement>(null);

  useEffect(() => {
    getMyProfile().then(setProfile).catch(() => { /* 离线时沿用已保存的资料 */ });
  }, [setProfile]);

  useEffect(() => {
    if (notice?.type !== 'success') return;
    const t = setTimeout(() => setNotice(null), 2500);
    return () => clearTimeout(t);
  }, [notice]);

  const handleAvatar = async (file: File | undefined) => {
    if (!file) return;
    if (!file.type.startsWith('image/')) {
      setNotice({ type: 'error', text: '请选择图片文件' });
      return;
    }
    if (file.size > MAX_AVATAR_BYTES) {
      setNotice({ type: 'error', text: '图片不能超过 10MB' });
      return;
    }
    setUploading(true);
    setNotice(null);
    try {
      const uploaded = await uploadFile(file, uid, token);
      setProfile(await updateProfile({ avatarFileId: uploaded.file_id }));
      setNotice({ type: 'success', text: '头像已更新' });
    } catch (err) {
      setNotice({ type: 'error', text: errorMessage(err, '头像上传失败') });
    } finally {
      setUploading(false);
    }
  };

  const saveNickname = async () => {
    const next = nameDraft.trim();
    if (!next) {
      setNotice({ type: 'error', text: '昵称不能为空' });
      return;
    }
    if ([...next].length > MAX_NICKNAME) {
      setNotice({ type: 'error', text: `昵称最多 ${MAX_NICKNAME} 个字` });
      return;
    }
    if (next === nickname) {
      setEditingName(false);
      return;
    }
    setSavingName(true);
    try {
      setProfile(await updateProfile({ nickname: next }));
      setEditingName(false);
      setNotice({ type: 'success', text: '昵称已更新' });
    } catch (err) {
      setNotice({ type: 'error', text: errorMessage(err, '修改失败') });
    } finally {
      setSavingName(false);
    }
  };

  const copyUid = async () => {
    try {
      await navigator.clipboard.writeText(uid);
      setNotice({ type: 'success', text: '用户 ID 已复制' });
    } catch {
      setNotice({ type: 'error', text: '复制失败' });
    }
  };

  return (
    <section className="bg-white dark:bg-gray-900 rounded-xl border border-gray-200 dark:border-gray-800 overflow-hidden">
      <div className="px-5 py-4 border-b border-gray-100 dark:border-gray-800">
        <div className="flex items-center gap-2">
          <User className="w-4 h-4 text-gray-400 dark:text-gray-500" />
          <h2 className="text-sm font-semibold text-gray-900 dark:text-gray-100">个人信息</h2>
        </div>
      </div>
      <div className="p-5 space-y-3">
        <div className="flex items-center justify-between">
          <span className="text-sm text-gray-500 dark:text-gray-400">头像</span>
          <button
            type="button"
            onClick={() => fileInput.current?.click()}
            disabled={uploading}
            title="更换头像"
            className="relative group rounded-full focus:outline-none focus:ring-2 focus:ring-primary-400"
          >
            <UserAvatar uid={uid} name={nickname} avatarUrl={avatarUrl} className="w-14 h-14 text-lg" />
            <span
              className={`absolute inset-0 rounded-full bg-black/40 flex items-center justify-center text-white transition-opacity ${
                uploading ? 'opacity-100' : 'opacity-0 group-hover:opacity-100'
              }`}
            >
              {uploading ? <span className="text-xs">上传中</span> : <Camera className="w-5 h-5" />}
            </span>
          </button>
          <input
            ref={fileInput}
            type="file"
            accept="image/*"
            className="hidden"
            onChange={(e) => {
              handleAvatar(e.target.files?.[0]);
              e.target.value = '';
            }}
          />
        </div>

        <div className="flex items-center justify-between gap-3 py-1">
          <span className="text-sm text-gray-500 dark:text-gray-400 flex-shrink-0">昵称</span>
          {editingName ? (
            <div className="flex items-center gap-1 flex-1 max-w-xs">
              <input
                value={nameDraft}
                onChange={(e) => setNameDraft(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') saveNickname();
                  if (e.key === 'Escape') setEditingName(false);
                }}
                maxLength={64}
                autoFocus
                className={inputClass}
              />
              <button onClick={saveNickname} disabled={savingName} className="p-1.5 rounded-lg text-green-600 hover:bg-green-50 dark:hover:bg-green-900/20 disabled:opacity-50" title="保存">
                <Check className="w-4 h-4" />
              </button>
              <button onClick={() => setEditingName(false)} className="p-1.5 rounded-lg text-gray-400 hover:bg-gray-100 dark:hover:bg-gray-800" title="取消">
                <X className="w-4 h-4" />
              </button>
            </div>
          ) : (
            <button
              onClick={() => {
                setNameDraft(nickname);
                setEditingName(true);
              }}
              className="flex items-center gap-1.5 text-sm font-medium text-gray-900 dark:text-gray-100 hover:text-primary-600 min-w-0"
            >
              <span className="truncate">{nickname}</span>
              <Pencil className="w-3.5 h-3.5 text-gray-400 flex-shrink-0" />
            </button>
          )}
        </div>

        <div className="flex items-center justify-between py-1">
          <span className="text-sm text-gray-500 dark:text-gray-400">用户 ID</span>
          <button onClick={copyUid} className="flex items-center gap-1.5 text-sm font-mono text-gray-600 dark:text-gray-400 hover:text-primary-600" title="复制">
            {uid}
            <Copy className="w-3.5 h-3.5" />
          </button>
        </div>

        <EmailRow email={email} onBound={(p) => { setProfile(p); setNotice({ type: 'success', text: '邮箱已绑定' }); }} />

        {notice && (
          <div className={`flex items-center gap-1.5 text-xs ${notice.type === 'success' ? 'text-green-600' : 'text-red-500'}`}>
            {notice.type === 'success' ? <Check className="w-3.5 h-3.5" /> : <X className="w-3.5 h-3.5" />}
            {notice.text}
          </div>
        )}
      </div>
    </section>
  );
}

function EmailRow({ email, onBound }: { email: string; onBound: (p: Awaited<ReturnType<typeof bindEmail>>) => void }) {
  const [open, setOpen] = useState(false);
  const [draft, setDraft] = useState('');
  const [code, setCode] = useState('');
  const [sending, setSending] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState('');
  const [info, setInfo] = useState('');
  const cooldown = useCountdown();

  const close = () => {
    setOpen(false);
    setCode('');
    setError('');
    setInfo('');
  };

  const sendCode = async () => {
    setError('');
    setInfo('');
    if (!isValidEmail(draft)) {
      setError('请输入正确的邮箱');
      return;
    }
    setSending(true);
    try {
      await sendBindEmailCode(draft.trim());
      setInfo(`验证码已发送至 ${draft.trim()}`);
      cooldown.start(CODE_COOLDOWN);
    } catch (err) {
      setError(errorMessage(err, '发送失败'));
      if (err instanceof ApiError && err.retryAfter > 0) cooldown.start(err.retryAfter);
    } finally {
      setSending(false);
    }
  };

  const submit = async () => {
    setError('');
    if (!isValidEmail(draft)) {
      setError('请输入正确的邮箱');
      return;
    }
    if (code.length !== 6) {
      setError('请输入 6 位验证码');
      return;
    }
    setSubmitting(true);
    try {
      onBound(await bindEmail(draft.trim(), code));
      close();
    } catch (err) {
      setError(errorMessage(err, '绑定失败'));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="py-1 space-y-2">
      <div className="flex items-center justify-between gap-3">
        <span className="text-sm text-gray-500 dark:text-gray-400">邮箱</span>
        <div className="flex items-center gap-2 min-w-0">
          <span className={`text-sm truncate ${email ? 'text-gray-900 dark:text-gray-100' : 'text-gray-400'}`}>{email || '未绑定'}</span>
          {!open && (
            <button
              onClick={() => {
                setDraft(email);
                setOpen(true);
              }}
              className="flex items-center gap-1 text-xs text-primary-600 hover:text-primary-700 flex-shrink-0"
            >
              <Mail className="w-3.5 h-3.5" />
              {email ? '更换' : '绑定'}
            </button>
          )}
        </div>
      </div>
      {open && (
        <div className="space-y-2 pl-6">
          {!email && <p className="text-xs text-gray-400">绑定后可用邮箱登录和找回密码</p>}
          <input type="email" value={draft} onChange={(e) => setDraft(e.target.value)} placeholder="you@example.com" autoFocus className={inputClass} />
          <div className="flex gap-2">
            <input
              value={code}
              inputMode="numeric"
              autoComplete="one-time-code"
              onChange={(e) => setCode(e.target.value.replace(/\D/g, '').slice(0, 6))}
              placeholder="6 位验证码"
              className={`${inputClass} flex-1 min-w-0`}
            />
            <button
              onClick={sendCode}
              disabled={sending || cooldown.left > 0 || !isValidEmail(draft)}
              className="px-3 rounded-lg border border-primary-500 text-primary-600 text-xs font-medium whitespace-nowrap hover:bg-primary-50 dark:hover:bg-primary-900/20 disabled:opacity-50 disabled:cursor-not-allowed"
            >
              {sending ? '发送中...' : cooldown.left > 0 ? `${cooldown.left} 秒后重发` : '获取验证码'}
            </button>
          </div>
          {error && <p className="text-xs text-red-500">{error}</p>}
          {info && !error && <p className="text-xs text-green-600">{info}</p>}
          <div className="flex gap-2">
            <button
              onClick={submit}
              disabled={submitting}
              className="px-4 py-2 bg-primary-500 text-white text-sm font-medium rounded-lg hover:bg-primary-600 disabled:opacity-50 transition-colors"
            >
              {submitting ? '绑定中...' : '确认绑定'}
            </button>
            <button onClick={close} className="px-4 py-2 text-sm text-gray-500 hover:text-gray-700 transition-colors">
              取消
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
