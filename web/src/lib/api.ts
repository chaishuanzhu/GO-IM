/**
 * REST 接口的 HTTP API 客户端。
 */

import type { LoginResponse, User, UserProfile, EmailCodePurpose, GroupInfo, GroupListItem, UploadResponse, SearchResponse, SearchParams, AdminStats, AdminUsersResponse, AdminBrowseResponse } from '@/types';
import { getToken, getStoredUid } from './auth';
import { LEGACY_UPLOAD_SIZE, MAX_UPLOAD_SIZE } from './constants';

type RequestMethod = 'GET' | 'POST';

/** 网关错误：[message] 已转成中文提示，[body] 为原始响应文本。 */
export class ApiError extends Error {
  readonly status: number;
  readonly body: string;
  readonly retryAfter: number;

  constructor(status: number, body: string, retryAfter = 0) {
    super(errorText(status, body));
    this.status = status;
    this.body = body;
    this.retryAfter = retryAfter;
  }
}

const ERROR_TEXTS: [string, string][] = [
  ['invalid account or password', '账号或密码错误'],
  ['account disabled', '账号已被禁用'],
  ['password required', '请输入密码'],
  ['email already registered', '该邮箱已被注册'],
  ['email not registered', '该邮箱未注册'],
  ['invalid or expired verification code', '验证码错误或已过期'],
  ['requested too frequently', '验证码发送太频繁，请稍后再试'],
  ['too many verification codes', '验证码发送次数已达上限，请稍后再试'],
  ['failed to send email', '邮件发送失败，请稍后重试'],
  ['invalid email', '邮箱格式不正确'],
  ['password must be at least', '密码至少 6 位'],
  ['password too long', '密码过长'],
  ['nickname required', '昵称不能为空'],
  ['nickname too long', '昵称最多 32 个字'],
  ['nickname contains invalid', '昵称包含不支持的字符'],
  ['avatar must be an image', '头像必须是图片'],
  ['avatar file not found', '头像图片无效，请重新上传'],
  ['query too long', '搜索内容过长'],
  ['friend request already exists', '已发送过请求或已是好友'],
  ['already friends', '已经是好友'],
  ['cannot friend yourself', '不能添加自己为好友'],
  ['user not found', '用户不存在'],
  ['not available', '服务端未启用该功能'],
];

function errorText(status: number, body: string): string {
  const lower = body.toLowerCase();
  const hit = ERROR_TEXTS.find(([key]) => lower.includes(key));
  if (hit) return hit[1];
  switch (status) {
    case 401: return '登录已过期，请重新登录';
    case 403: return '没有权限';
    case 429: return '操作过于频繁，请稍后再试';
    case 503: return '服务端未启用该功能';
    default: return `请求失败 (${status}): ${body.trim() || '未知错误'}`;
  }
}

function getAuthParams(): URLSearchParams {
  // 必须与 auth.ts 中定义的键一致 (im_uid、im_token —— 下划线)。
  const uid = getStoredUid() || '';
  const token = getToken() || '';
  return new URLSearchParams({ uid, token });
}

async function request<T>(
  path: string,
  method: RequestMethod = 'GET',
  body?: URLSearchParams | FormData,
): Promise<T> {
  const headers: Record<string, string> = {};

  // 为每个请求附加认证参数 (uid + token)。
  const authParams = getAuthParams();
  let url = path;
  if (method === 'GET') {
    // 将认证参数附加到查询字符串。
    const sep = path.includes('?') ? '&' : '?';
    url = path + sep + authParams.toString();
  } else if (body instanceof URLSearchParams) {
    // 将认证参数合并进表单体。
    authParams.forEach((value, key) => { if (value) body.set(key, value); });
  }
  // FormData:不注入认证参数 —— 调用方必须手动传入 uid/token。

  if (body instanceof URLSearchParams) {
    headers['Content-Type'] = 'application/x-www-form-urlencoded';
  }
  // FormData 会自带带 boundary 的 Content-Type

  const res = await fetch(url, {
    method,
    headers,
    body: body instanceof URLSearchParams ? body.toString() : (body as FormData),
  });

  if (!res.ok) {
    const text = await res.text();
    throw new ApiError(res.status, text, Number(res.headers.get('Retry-After')) || 0);
  }

  return res.json();
}

// ---- 认证 ----

/** 不带当前会话凭据的表单 POST（登录、注册、找回密码）。 */
async function publicPost<T>(path: string, fields: Record<string, string>): Promise<T> {
  const res = await fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams(fields).toString(),
  });
  if (!res.ok) {
    throw new ApiError(res.status, await res.text(), Number(res.headers.get('Retry-After')) || 0);
  }
  return res.json();
}

/** [account] 为邮箱或用户 ID。 */
export async function login(account: string, password: string): Promise<LoginResponse> {
  return publicPost<LoginResponse>('/login', { account, password });
}

export async function register(email: string, code: string, password: string): Promise<LoginResponse> {
  return publicPost<LoginResponse>('/register', { email, code, password });
}

/** 注册 / 找回密码验证码；绑定邮箱用 [sendBindEmailCode]（需登录）。 */
export async function sendEmailCode(email: string, purpose: Exclude<EmailCodePurpose, 'bind_email'>): Promise<{ status: string }> {
  return publicPost('/auth/email-code', { email, purpose });
}

export async function resetPassword(email: string, code: string, newPassword: string): Promise<{ status: string }> {
  return publicPost('/password/reset', { email, code, new_password: newPassword });
}

export async function changePassword(oldPassword: string, newPassword: string): Promise<{ status: string }> {
  const params = new URLSearchParams({ old_password: oldPassword, new_password: newPassword });
  // request() 自动注入 uid + token —— 后端通过 authenticateRequest 校验 JWT
  return request('/change-password', 'POST', params);
}

// ---- 用户 ----

/** 自己的完整资料（含邮箱）。 */
export async function getMyProfile(): Promise<UserProfile> {
  return request('/user/profile');
}

/** 批量读取公开资料（最多 200 个）。 */
export async function getProfiles(uids: string[]): Promise<{ users: UserProfile[] }> {
  return request(`/user/profiles?uids=${encodeURIComponent(uids.join(','))}`);
}

/** 修改昵称 / 头像；[avatarFileId] 为 /upload 返回的 file_id，"0" 清除头像。 */
export async function updateProfile(fields: { nickname?: string; avatarFileId?: string }): Promise<UserProfile> {
  const params = new URLSearchParams();
  if (fields.nickname !== undefined) params.set('nickname', fields.nickname);
  if (fields.avatarFileId !== undefined) params.set('avatar_file_id', fields.avatarFileId);
  return request('/user/profile', 'POST', params);
}

/** 按昵称（包含）、邮箱或用户 ID（精确）搜索用户。 */
export async function searchUsers(q: string, limit = 20): Promise<{ users: UserProfile[] }> {
  return request(`/user/search?q=${encodeURIComponent(q)}&limit=${limit}`);
}

export async function sendBindEmailCode(email: string): Promise<{ status: string }> {
  return request('/auth/email-code', 'POST', new URLSearchParams({ email, purpose: 'bind_email' }));
}

export async function bindEmail(email: string, code: string): Promise<UserProfile> {
  return request('/user/email', 'POST', new URLSearchParams({ email, code }));
}

export async function getOnlineUsers(): Promise<{ count: number; users: string[] }> {
  return request('/online');
}

export async function getHealth(): Promise<{
  status: string;
  connections: number;
  dependencies: Record<string, string>;
  memory: { alloc_mb: number; goroutines: number };
}> {
  return request('/health');
}

// ---- 群组 ----

export async function createGroup(name: string, members?: string[]): Promise<GroupInfo> {
  const params = new URLSearchParams({ name });
  if (members && members.length > 0) {
    params.set('members', members.join(','));
  }
  return request('/group/create', 'POST', params);
}

export async function joinGroup(groupId: string): Promise<{ ok: string }> {
  const params = new URLSearchParams({ group_id: groupId });
  return request('/group/join', 'POST', params);
}

export async function inviteGroupMember(groupId: string, targetUid: string): Promise<{ ok: string }> {
  const params = new URLSearchParams({ group_id: groupId, target_uid: targetUid });
  return request('/group/invite', 'POST', params);
}

export async function leaveGroup(groupId: string): Promise<{ ok: string }> {
  const params = new URLSearchParams({ group_id: groupId });
  return request('/group/leave', 'POST', params);
}

export async function kickGroupMember(groupId: string, targetUid: string): Promise<{ ok: string }> {
  const params = new URLSearchParams({ group_id: groupId, target_uid: targetUid });
  return request('/group/kick', 'POST', params);
}

export async function renameGroup(groupId: string, name: string): Promise<{ ok: string; name: string }> {
  const params = new URLSearchParams({ group_id: groupId, name });
  return request('/group/rename', 'POST', params);
}

export async function transferGroup(groupId: string, toUid: string): Promise<{ ok: string; owner_uid: string }> {
  const params = new URLSearchParams({ group_id: groupId, to_uid: toUid });
  return request('/group/transfer', 'POST', params);
}

export async function getGroupMembers(groupId: string): Promise<{ group_id: string; members: string[] }> {
  return request(`/group/members?group_id=${encodeURIComponent(groupId)}`);
}

export async function getGroupList(): Promise<{ groups: GroupListItem[] }> {
  return request('/group/list');
}

// ---- 文件 ----

interface PresignResponse {
  file_id: string;
  upload_url: string;
  method: string;
  headers: Record<string, string>;
}

/** 直传不可用（服务端未开启或对象存储不可达），可回退到网关中转上传。 */
class DirectUploadUnavailable extends Error {}

/**
 * 上传文件：优先 presign → PUT 直传对象存储 → complete；
 * 服务端未开启直传或对象存储不可达时，对不超过 LEGACY_UPLOAD_SIZE 的文件回退到 POST /upload。
 */
export async function uploadFile(
  file: File,
  uid: string,
  token: string,
  onProgress?: (pct: number) => void,
): Promise<UploadResponse> {
  if (file.size > MAX_UPLOAD_SIZE) {
    throw new Error(`文件大小超过限制（最大 ${MAX_UPLOAD_SIZE / 1024 / 1024}MB）`);
  }
  try {
    return await uploadDirect(file, uid, token, onProgress);
  } catch (err) {
    if (!(err instanceof DirectUploadUnavailable)) throw err;
    if (file.size > LEGACY_UPLOAD_SIZE) {
      throw new Error(`当前服务器仅支持 ${LEGACY_UPLOAD_SIZE / 1024 / 1024}MB 以内的文件`);
    }
    return uploadViaGateway(file, uid, token, onProgress);
  }
}

async function postAuthForm(path: string, uid: string, token: string, fields: Record<string, string>): Promise<Response> {
  const body = new URLSearchParams({ ...fields, uid, token });
  return fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: body.toString(),
  });
}

async function uploadDirect(
  file: File,
  uid: string,
  token: string,
  onProgress?: (pct: number) => void,
): Promise<UploadResponse> {
  const presignRes = await postAuthForm('/file/presign', uid, token, {
    name: file.name,
    size: String(file.size),
    mime: file.type,
  });
  if (presignRes.status === 501 || presignRes.status === 404 || presignRes.status === 405) {
    throw new DirectUploadUnavailable('direct upload not enabled');
  }
  if (!presignRes.ok) {
    throw new Error(`上传失败 (${presignRes.status}): ${(await presignRes.text()) || '未知错误'}`);
  }
  const presign: PresignResponse = await presignRes.json();

  await new Promise<void>((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open(presign.method || 'PUT', presign.upload_url);
    Object.entries(presign.headers ?? {}).forEach(([k, v]) => xhr.setRequestHeader(k, v));
    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable && onProgress) {
        onProgress(Math.min(99, Math.round((e.loaded / e.total) * 100)));
      }
    };
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) resolve();
      else reject(new Error(`上传失败 (${xhr.status})`));
    };
    // 网络错误多为对象存储域名不可达或 CORS 未配置
    xhr.onerror = () => reject(new DirectUploadUnavailable('object storage unreachable'));
    xhr.send(file);
  });

  const dims = file.type.startsWith('image/') ? await imageDimensions(file) : null;
  const completeRes = await postAuthForm('/file/complete', uid, token, {
    file_id: presign.file_id,
    ...(dims ? { width: String(dims.width), height: String(dims.height) } : {}),
  });
  if (!completeRes.ok) {
    throw new Error(`上传失败 (${completeRes.status}): ${(await completeRes.text()) || '未知错误'}`);
  }
  return completeRes.json();
}

/** 读取图片尺寸，供服务端无法从文件头解析时使用；失败返回 null。 */
function imageDimensions(file: File): Promise<{ width: number; height: number } | null> {
  return new Promise((resolve) => {
    const url = URL.createObjectURL(file);
    const img = new Image();
    img.onload = () => {
      resolve({ width: img.naturalWidth, height: img.naturalHeight });
      URL.revokeObjectURL(url);
    };
    img.onerror = () => {
      resolve(null);
      URL.revokeObjectURL(url);
    };
    img.src = url;
  });
}

function uploadViaGateway(
  file: File,
  uid: string,
  token: string,
  onProgress?: (pct: number) => void,
): Promise<UploadResponse> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    const formData = new FormData();
    formData.append('uid', uid);
    formData.append('token', token);
    formData.append('file', file);

    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable && onProgress) {
        onProgress(Math.round((e.loaded / e.total) * 100));
      }
    };

    xhr.onload = () => {
      if (xhr.status === 200) {
        try {
          resolve(JSON.parse(xhr.responseText));
        } catch {
          reject(new Error('服务器响应格式异常'));
        }
      } else {
        reject(new Error(`上传失败 (${xhr.status}): ${xhr.responseText || '未知错误'}`));
      }
    };

    xhr.onerror = () => reject(new Error('上传失败，网络连接异常'));
    xhr.open('POST', '/upload');
    xhr.send(formData);
  });
}

export function getFileURL(fileId: string, uid: string, token: string, thumb = false): string {
  const params = new URLSearchParams({ id: fileId, uid, token });
  if (thumb) params.set('thumb', '1');
  return `/file?${params.toString()}`;
}

// ---- 搜索 ----

export async function searchMessages(
  params: SearchParams,
): Promise<SearchResponse> {
  const sp = new URLSearchParams({ q: params.q });
  if (params.peer) sp.set('peer', params.peer);
  if (params.chatType) sp.set('chat_type', String(params.chatType));
  if (params.msgType) sp.set('msg_type', String(params.msgType));
  if (params.before) sp.set('before', String(params.before));
  if (params.after) sp.set('after', String(params.after));
  if (params.cursor) sp.set('cursor', String(params.cursor));
  if (params.limit) sp.set('limit', String(params.limit));

  // request() 通过 getAuthParams() 自动注入 uid + token
  return request(`/search?${sp.toString()}`);
}

// ---- 好友 ----

export interface FriendListResponse {
  uid: string;
  friends: { uid: string; friend_uid: string; status: number; created_at: number; nickname?: string; avatar_url?: string }[];
  pending_requests: { from_uid: string; nickname: string; avatar_url?: string; created_at: number }[];
}

export async function sendFriendRequest(toUid: string): Promise<{ status: string }> {
  const params = new URLSearchParams({ to_uid: toUid });
  return request('/friend/request', 'POST', params);
}

export async function acceptFriendRequest(fromUid: string): Promise<{ status: string }> {
  const params = new URLSearchParams({ from_uid: fromUid });
  return request('/friend/accept', 'POST', params);
}

export async function rejectFriendRequest(fromUid: string): Promise<{ status: string }> {
  const params = new URLSearchParams({ from_uid: fromUid });
  return request('/friend/reject', 'POST', params);
}

export async function removeFriend(friendUid: string): Promise<{ status: string }> {
  const params = new URLSearchParams({ friend_uid: friendUid });
  return request('/friend/remove', 'POST', params);
}

export async function getFriendList(): Promise<FriendListResponse> {
  return request('/friend/list');
}

// ---- 未读 ----

export async function getUnreadCounts(): Promise<{ uid: string; counts: Record<string, number> }> {
  return request('/unread');
}

// ---- 管理 ----

function adminParams(uid: string, token: string): URLSearchParams {
  return new URLSearchParams({ uid, token });
}

export async function getAdminStats(uid: string, token: string): Promise<AdminStats> {
  return request(`/admin/stats?${adminParams(uid, token).toString()}`);
}

export async function getAdminUsers(uid: string, token: string, offset = 0, limit = 50): Promise<AdminUsersResponse> {
  const params = adminParams(uid, token);
  params.set('offset', String(offset));
  params.set('limit', String(limit));
  return request(`/admin/users?${params.toString()}`);
}

export async function deleteAdminUser(uid: string, token: string, targetUid: string): Promise<{ status: string }> {
  const params = adminParams(uid, token);
  params.set('target_uid', targetUid);
  return request('/admin/users/delete', 'POST', params);
}

export async function getAdminMessages(uid: string, token: string, before = 0, limit = 50): Promise<AdminBrowseResponse> {
  const params = adminParams(uid, token);
  if (before > 0) params.set('before', String(before));
  params.set('limit', String(limit));
  return request(`/admin/messages?${params.toString()}`);
}

export async function deleteAdminMessage(uid: string, token: string, msgId: string): Promise<{ status: string }> {
  const params = adminParams(uid, token);
  params.set('msg_id', msgId);
  return request('/admin/messages/delete', 'POST', params);
}
