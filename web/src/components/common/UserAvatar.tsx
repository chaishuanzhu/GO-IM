import { useState } from 'react';
import { useProfile } from '@/stores/profileStore';
import { avatarThumbUrl, getAvatarLetters } from '@/lib/utils';

interface UserAvatarProps {
  uid: string;
  /** 资料未加载时用于生成首字母 */
  name?: string;
  /** 已知头像（如搜索结果）时直接使用，否则读资料缓存 */
  avatarUrl?: string;
  /** Tailwind 尺寸与字号，如 "w-10 h-10 text-sm" */
  className?: string;
}

/** 用户头像：有头像图片时显示缩略图，加载失败或未设置时显示首字母。 */
export default function UserAvatar({ uid, name, avatarUrl, className = 'w-10 h-10 text-sm' }: UserAvatarProps) {
  const profile = useProfile(avatarUrl === undefined ? uid : undefined);
  const url = avatarUrl ?? profile?.avatar_url ?? '';
  const title = profile?.nickname || name || uid;
  const [failed, setFailed] = useState<string | null>(null);
  const showImage = url !== '' && failed !== url;

  return (
    <div
      className={`relative rounded-full bg-primary-500 flex items-center justify-center text-white font-semibold flex-shrink-0 overflow-hidden ${className}`}
    >
      {getAvatarLetters(title)}
      {showImage && (
        <img
          src={avatarThumbUrl(url)}
          alt=""
          loading="lazy"
          onError={() => setFailed(url)}
          className="absolute inset-0 w-full h-full object-cover"
        />
      )}
    </div>
  );
}
