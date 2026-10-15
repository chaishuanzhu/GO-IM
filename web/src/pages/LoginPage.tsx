import { useState, useEffect, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { ApiError, login, register, resetPassword, sendEmailCode } from '@/lib/api';
import { useAuthStore } from '@/stores/authStore';
import { useCountdown } from '@/hooks/useCountdown';
import { isValidEmail } from '@/lib/utils';
import { MessageCircle } from 'lucide-react';

type AuthMode = 'login' | 'register' | 'reset';

const CODE_LENGTH = 6;
const MIN_PASSWORD = 6;
const CODE_COOLDOWN = 60;

const inputClass =
  'w-full px-4 py-3 rounded-lg border border-gray-300 dark:border-gray-700 focus:ring-2 focus:ring-primary-500 focus:border-primary-500 outline-none transition-all bg-white dark:bg-gray-800 text-gray-900 dark:text-gray-100';
const labelClass = 'block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1';

export default function LoginPage() {
  const [mode, setMode] = useState<AuthMode>('login');
  const [account, setAccount] = useState('');
  const [email, setEmail] = useState('');
  const [code, setCode] = useState('');
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [loading, setLoading] = useState(false);
  const [sending, setSending] = useState(false);
  const [error, setError] = useState('');
  const [info, setInfo] = useState('');
  const cooldown = useCountdown();
  const authLogin = useAuthStore((s) => s.login);
  const isLoggedIn = useAuthStore((s) => s.isLoggedIn);
  const navigate = useNavigate();

  // 挂载时恢复已有会话
  useEffect(() => {
    useAuthStore.getState().restore();
  }, []);

  useEffect(() => {
    if (isLoggedIn) navigate('/chat', { replace: true });
  }, [isLoggedIn, navigate]);

  if (isLoggedIn) return null;

  const switchMode = (next: AuthMode) => {
    // 登录框里填的是邮箱时带到注册 / 找回密码
    if (next !== 'login' && !email && account.includes('@')) setEmail(account.trim());
    setMode(next);
    setError('');
    setInfo('');
    setCode('');
    setPassword('');
    setConfirmPassword('');
  };

  const handleSendCode = async () => {
    setError('');
    setInfo('');
    if (!isValidEmail(email)) {
      setError('请输入正确的邮箱');
      return;
    }
    setSending(true);
    try {
      await sendEmailCode(email.trim(), mode === 'reset' ? 'reset_password' : 'register');
      setInfo(`验证码已发送至 ${email.trim()}，10 分钟内有效`);
      cooldown.start(CODE_COOLDOWN);
    } catch (err) {
      setError(err instanceof Error ? err.message : '发送失败');
      if (err instanceof ApiError && err.retryAfter > 0) cooldown.start(err.retryAfter);
    } finally {
      setSending(false);
    }
  };

  const validate = (): string => {
    if (mode === 'login') return account.trim() ? '' : '请输入邮箱或用户 ID';
    if (!isValidEmail(email)) return '请输入正确的邮箱';
    if (code.length !== CODE_LENGTH) return '请输入 6 位验证码';
    if (password.length < MIN_PASSWORD) return `密码至少 ${MIN_PASSWORD} 位`;
    if (password !== confirmPassword) return '两次输入的密码不一致';
    return '';
  };

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setError('');
    setInfo('');
    const invalid = validate();
    if (invalid) {
      setError(invalid);
      return;
    }

    setLoading(true);
    try {
      if (mode === 'login') {
        authLogin(await login(account.trim(), password));
      } else if (mode === 'register') {
        authLogin(await register(email.trim(), code, password));
      } else {
        await resetPassword(email.trim(), code, password);
        setMode('login');
        setAccount(email.trim());
        setCode('');
        setPassword('');
        setConfirmPassword('');
        setInfo('密码已重置，请用新密码登录');
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : '操作失败');
    } finally {
      setLoading(false);
    }
  };

  const subtitle = {
    login: '欢迎回来，请登录您的账户',
    register: '使用邮箱注册，系统将自动分配用户 ID',
    reset: '通过邮箱验证码重置密码',
  }[mode];

  const tabClass = (active: boolean) =>
    `flex-1 py-4 text-sm font-semibold transition-colors ${
      active
        ? 'text-primary-600 dark:text-primary-400 border-b-2 border-primary-600 bg-primary-50/50 dark:bg-primary-900/20'
        : 'text-gray-400 dark:text-gray-500 hover:text-gray-600 dark:hover:text-gray-300'
    }`;

  return (
    <div className="min-h-screen flex items-center justify-center bg-gradient-to-br from-primary-50 dark:from-gray-950 to-blue-100 dark:to-gray-900">
      <div className="w-full max-w-md mx-4">
        <div className="bg-white dark:bg-gray-900 rounded-2xl shadow-lg overflow-hidden">
          {/* 标签切换 */}
          <div className="flex border-b border-gray-100 dark:border-gray-800">
            <button type="button" onClick={() => switchMode('login')} className={tabClass(mode !== 'register')}>
              {mode === 'reset' ? '找回密码' : '登录'}
            </button>
            <button type="button" onClick={() => switchMode('register')} className={tabClass(mode === 'register')}>
              注册
            </button>
          </div>

          <div className="p-8 space-y-6">
            {/* 标志 */}
            <div className="text-center space-y-2">
              <div className="inline-flex items-center justify-center w-16 h-16 rounded-full bg-primary-100">
                <MessageCircle className="w-8 h-8 text-primary-600" />
              </div>
              <h1 className="text-2xl font-bold text-gray-900 dark:text-gray-100">IM 即时通讯</h1>
              <p className="text-sm text-gray-500 dark:text-gray-400">{subtitle}</p>
            </div>

            <form onSubmit={handleSubmit} className="space-y-4">
              {mode === 'login' ? (
                <div>
                  <label htmlFor="account" className={labelClass}>邮箱或用户 ID</label>
                  <input
                    id="account"
                    type="text"
                    autoComplete="username"
                    value={account}
                    onChange={(e) => setAccount(e.target.value)}
                    placeholder="例如: alice@example.com 或 100001"
                    autoFocus
                    className={inputClass}
                  />
                </div>
              ) : (
                <>
                  <div>
                    <label htmlFor="email" className={labelClass}>邮箱</label>
                    <input
                      id="email"
                      type="email"
                      autoComplete="email"
                      value={email}
                      onChange={(e) => setEmail(e.target.value)}
                      placeholder="you@example.com"
                      autoFocus
                      className={inputClass}
                    />
                  </div>
                  <div>
                    <label htmlFor="code" className={labelClass}>验证码</label>
                    <div className="flex gap-2">
                      <input
                        id="code"
                        type="text"
                        inputMode="numeric"
                        autoComplete="one-time-code"
                        value={code}
                        onChange={(e) => setCode(e.target.value.replace(/\D/g, '').slice(0, CODE_LENGTH))}
                        placeholder="6 位数字"
                        className={`${inputClass} flex-1 min-w-0`}
                      />
                      <button
                        type="button"
                        onClick={handleSendCode}
                        disabled={sending || cooldown.left > 0 || !isValidEmail(email)}
                        className="px-4 rounded-lg border border-primary-600 text-primary-600 dark:text-primary-400 text-sm font-medium whitespace-nowrap hover:bg-primary-50 dark:hover:bg-primary-900/20 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
                      >
                        {sending ? '发送中...' : cooldown.left > 0 ? `${cooldown.left} 秒后重发` : '获取验证码'}
                      </button>
                    </div>
                  </div>
                </>
              )}

              <div>
                <label htmlFor="password" className={labelClass}>
                  {mode === 'reset' ? '新密码' : '密码'}
                </label>
                <input
                  id="password"
                  type="password"
                  autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  placeholder={mode === 'login' ? '输入密码' : `至少 ${MIN_PASSWORD} 位`}
                  className={inputClass}
                />
              </div>

              {mode !== 'login' && (
                <div>
                  <label htmlFor="confirmPassword" className={labelClass}>确认密码</label>
                  <input
                    id="confirmPassword"
                    type="password"
                    autoComplete="new-password"
                    value={confirmPassword}
                    onChange={(e) => setConfirmPassword(e.target.value)}
                    placeholder="再次输入密码"
                    className={inputClass}
                  />
                </div>
              )}

              {error && (
                <div className="p-3 rounded-lg bg-red-50 dark:bg-red-900/20 text-red-600 dark:text-red-400 text-sm">{error}</div>
              )}
              {info && !error && (
                <div className="p-3 rounded-lg bg-green-50 dark:bg-green-900/20 text-green-600 dark:text-green-400 text-sm">{info}</div>
              )}

              <button
                type="submit"
                disabled={loading}
                className="w-full py-3 px-4 bg-primary-600 hover:bg-primary-700 text-white font-medium rounded-lg transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
              >
                {loading ? '处理中...' : { login: '登 录', register: '注 册', reset: '重置密码' }[mode]}
              </button>
            </form>

            <div className="text-center text-xs text-gray-400 dark:text-gray-500">
              {mode === 'login' && (
                <button type="button" onClick={() => switchMode('reset')} className="hover:text-primary-600">
                  忘记密码？
                </button>
              )}
              {mode === 'register' && '注册后可在设置中修改昵称和头像'}
              {mode === 'reset' && (
                <button type="button" onClick={() => switchMode('login')} className="hover:text-primary-600">
                  返回登录
                </button>
              )}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
