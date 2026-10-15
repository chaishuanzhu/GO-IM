import type { GroupNotification } from '@/types';
import { tryParseJSON } from '@/lib/utils';
import type { ReactNode } from 'react';
import UserName from '@/components/common/UserName';

interface SystemNoticeProps {
  notification: GroupNotification;
}

/** 从消息内容 JSON 渲染富文本系统通知 */
export function renderSystemNotice(content: string): ReactNode {
  const parsed = tryParseJSON<{ type?: string; uid?: string; name?: string; from_uid?: string; nickname?: string; username?: string }>(content);
  if (!parsed) return null;

  switch (parsed.type) {
    case 'member_joined':
      return <>{parsed.uid ? <UserName uid={parsed.uid} /> : '用户'} 加入了群聊</>;
    case 'member_left':
      return <>{parsed.uid ? <UserName uid={parsed.uid} /> : '用户'} 退出了群聊</>;
    case 'friend_request':
      return <>{parsed.from_uid ? <UserName uid={parsed.from_uid} fallback={parsed.nickname || parsed.username} /> : '用户'} 请求添加好友</>;
    case 'friend_accepted':
      return <>{parsed.uid ? <UserName uid={parsed.uid} /> : '用户'} 已同意好友请求</>;
    case 'group_created':
      return <>群组 &ldquo;{parsed.name || '未命名'}&rdquo; 已创建</>;
    default:
      return null;
  }
}

export default function SystemNotice({ notification }: SystemNoticeProps) {
  const { type, uid } = notification;

  const text =
    type === 'member_joined'
      ? <><UserName uid={uid} /> 加入了群聊</>
      : type === 'member_left'
        ? <><UserName uid={uid} /> 退出了群聊</>
        : '';

  return (
    <div className="flex justify-center py-1">
      <span className="px-3 py-1 text-xs text-gray-500 dark:text-gray-400 bg-gray-100 dark:bg-gray-800 rounded-full">
        {text}
      </span>
    </div>
  );
}
