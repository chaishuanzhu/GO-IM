import { useDisplayName } from '@/stores/profileStore';

/** 行内显示用户昵称（资料未加载时显示 [fallback] 或 UID）。 */
export default function UserName({ uid, fallback }: { uid: string; fallback?: string }) {
  return <>{useDisplayName(uid, fallback)}</>;
}
