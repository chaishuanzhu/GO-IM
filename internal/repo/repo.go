// Package repo 定义数据持久化接口与 MySQL 实现。
package repo

import (
	"context"
	"errors"

	"github.com/im/api/proto"
)

var (
	// ErrEmailTaken 表示邮箱已被其他用户使用。
	ErrEmailTaken = errors.New("email already registered")
	// ErrUserNotFound 表示目标用户不存在。
	ErrUserNotFound = errors.New("user not found")
)

// User 表示一个已注册的用户。
type User struct {
	UID          string `json:"uid"`
	Nickname     string `json:"nickname"`
	Email        string `json:"email,omitempty"` // 空 = 未绑定（早期注册的用户）
	AvatarFileID int64  `json:"-"`               // 0 = 未设置；对外以 avatar_url 表示
	PasswordHash string `json:"-"`
	Role         string `json:"role"` // "user" 或 "admin"
	IsDisabled   bool   `json:"is_disabled"`
	// MustChangePassword 为 true 时客户端登录后须先改密码（自动创建的管理员账号）；改密或重置后清除。
	MustChangePassword bool  `json:"-"`
	CreatedAt          int64 `json:"created_at"` // 毫秒级 Unix 时间戳
	UpdatedAt          int64 `json:"updated_at"` // 资料最后修改时间（毫秒），0 = 未修改过
}

// UserStore 管理用户持久化。
type UserStore interface {
	// Create 以 u.UID 插入用户（早期自定义 ID 的路径，测试与工具使用）。
	Create(ctx context.Context, u *User) error
	// CreateWithAutoUID 分配自增 UID 并插入用户，成功后写回 u.UID；邮箱已占用返回 ErrEmailTaken。
	CreateWithAutoUID(ctx context.Context, u *User) error
	GetByUID(ctx context.Context, uid string) (*User, error)
	// GetByEmail 按邮箱（不区分大小写）获取用户，未找到返回 (nil, nil)。
	GetByEmail(ctx context.Context, email string) (*User, error)
	// GetUsers 批量获取用户，不存在的 UID 直接跳过。
	GetUsers(ctx context.Context, uids []string) ([]*User, error)
	// UpdateProfile 修改昵称和/或头像，nil 表示不修改；用户不存在返回 ErrUserNotFound。
	UpdateProfile(ctx context.Context, uid string, nickname *string, avatarFileID *int64) error
	// SetEmail 绑定或更换邮箱；已被他人占用返回 ErrEmailTaken。
	SetEmail(ctx context.Context, uid, email string) error
	// SearchUsers 按 UID / 邮箱精确匹配或昵称包含匹配搜索，最多返回 limit 条。
	SearchUsers(ctx context.Context, query string, limit int) ([]*User, error)
	UpdatePassword(ctx context.Context, uid, newPasswordHash string) error
	// 管理员方法
	ListUsers(ctx context.Context, offset, limit int) ([]*User, int, error)
	DeleteUser(ctx context.Context, uid string) error
	UpdateUserRole(ctx context.Context, uid, role string) error
	UpdateUserDisabled(ctx context.Context, uid string, disabled bool) error
	CountUsers(ctx context.Context) (int, error)
}

// SettingsStore 持久化管理后台可改的系统设置（键值对）。
type SettingsStore interface {
	GetSettings(ctx context.Context) (map[string]string, error)
	PutSettings(ctx context.Context, values map[string]string) error
}

// SearchParams 保存全文搜索参数。
type SearchParams struct {
	UID      string // 搜索用户（访问控制 —— 必须是参与者）
	Query    string // 搜索词
	Peer     string // 可选：限定到单个会话
	ChatType int32  // 可选过滤：0=全部，1=单聊，2=群聊
	MsgType  int32  // 可选过滤：0=全部
	Before   int64  // 可选：最大时间戳（毫秒），0 = 当前时间
	After    int64  // 可选：最小时间戳（毫秒）
	Limit    int    // 最大结果数，默认 20，最大 50
	Cursor   int64  // 键集分页：最后一条结果的 msg_id
}

// SearchResult 保存全文搜索的结果。
type SearchResult struct {
	Messages   []*proto.Message
	Count      int   // 本页结果数量
	NextCursor int64 // 0 = 没有更多页
}

// FriendStatus 表示好友请求的状态。
type FriendStatus int32

const (
	FriendStatusPending  FriendStatus = 0 // 请求已发送，等待响应
	FriendStatusAccepted FriendStatus = 1 // 请求已接受，已成为好友
	FriendStatusRejected FriendStatus = 2 // 请求已被拒绝
)

// Friend 表示一条好友关系。UID 始终是对方，Nickname / AvatarFileID 为对方资料。
type Friend struct {
	UID          string       `json:"uid"`
	FriendUID    string       `json:"friend_uid"`
	Status       FriendStatus `json:"status"`
	CreatedAt    int64        `json:"created_at"` // 毫秒级 Unix 时间戳
	Nickname     string       `json:"nickname"`
	AvatarFileID int64        `json:"-"`
	AvatarURL    string       `json:"avatar_url"` // 由网关按 AvatarFileID 填充
}

// FriendRequest 表示一条收到的好友请求（从接收方的角度）。
type FriendRequest struct {
	FromUID      string `json:"from_uid"`
	Nickname     string `json:"nickname"`   // 请求方昵称（用户不存在时为其 UID）
	CreatedAt    int64  `json:"created_at"` // 毫秒级 Unix 时间戳
	AvatarFileID int64  `json:"-"`
	AvatarURL    string `json:"avatar_url"` // 由网关按 AvatarFileID 填充
}

// FriendStore 管理好友关系与请求。
type FriendStore interface {
	// SendRequest 发送一条从 fromUID 到 toUID 的好友请求。
	// 如果两者之间任何方向已存在请求，则返回错误。
	SendRequest(ctx context.Context, fromUID, toUID string) error
	// AcceptRequest 接受一条待处理的好友请求。
	AcceptRequest(ctx context.Context, uid, fromUID string) error
	// RejectRequest 拒绝一条待处理的好友请求。
	RejectRequest(ctx context.Context, uid, fromUID string) error
	// RemoveFriend 移除一条好友关系（任意方向）。
	RemoveFriend(ctx context.Context, uid, friendUID string) error
	// GetFriends 返回一个用户的所有已接受好友。
	GetFriends(ctx context.Context, uid string) ([]*Friend, error)
	// GetPendingRequests 返回一个用户收到的所有待处理好友请求。
	GetPendingRequests(ctx context.Context, uid string) ([]*FriendRequest, error)
}

// FileStatus 表示上传文件的状态。
type FileStatus int8

const (
	FileStatusPending FileStatus = 0 // 已签发上传 URL，尚未确认
	FileStatusReady   FileStatus = 1 // 已上传并校验，可在消息中引用
)

// File 是一个上传到对象存储的文件的元数据。对象 key 即 FileID 的十进制字符串。
type File struct {
	FileID    int64
	OwnerUID  string
	Name      string
	Size      int64
	Mime      string
	Width     int
	Height    int
	HasThumb  bool
	Status    FileStatus
	IsAvatar  bool
	CreatedAt int64 // 毫秒
}

// FileStore 管理文件元数据与文件被哪些会话引用（用于下载鉴权）。
// 会话 key：单聊为两端 UID 排序后以 ":" 连接，群聊为群 ID。
type FileStore interface {
	CreateFile(ctx context.Context, f *File) error
	// GetFile 在文件不存在时返回 (nil, nil)。
	GetFile(ctx context.Context, fileID int64) (*File, error)
	MarkFileReady(ctx context.Context, fileID int64, mime string, width, height int) error
	SetFileThumb(ctx context.Context, fileID int64) error
	AddFileRef(ctx context.Context, fileID int64, convKey string) error
	FileConvKeys(ctx context.Context, fileID int64) ([]string, error)
	// StalePendingFiles 返回 createdBefore（毫秒）之前创建、仍未确认的文件 ID。
	StalePendingFiles(ctx context.Context, createdBefore int64, limit int) ([]int64, error)
	DeleteFile(ctx context.Context, fileID int64) error
	// MarkAvatar 把文件标记为头像：此后任何人都可通过 /avatar 读取，且不会被清理任务删除。
	MarkAvatar(ctx context.Context, fileID int64) error
}

// MessageStore 管理消息历史记录的持久化。
type MessageStore interface {
	Save(ctx context.Context, msg *proto.Message) error
	QueryHistory(ctx context.Context, uid1, uid2 string, before int64, limit int) ([]*proto.Message, error)
	QueryGroupHistory(ctx context.Context, groupID string, before int64, limit int) ([]*proto.Message, error)
	SearchMessages(ctx context.Context, params *SearchParams) (*SearchResult, error)
	RecallMessage(ctx context.Context, msgID int64, fromUID string, recallWindowMs int64) error
	UpdateMessageContent(ctx context.Context, msgID int64, fromUID, newContent string) error
	// 管理员方法
	BrowseMessages(ctx context.Context, before int64, limit int) ([]*proto.Message, error)
	DeleteMessage(ctx context.Context, msgID int64) error
	CountMessages(ctx context.Context) (int, error)
}

// ConversationSummary 是管理后台里某个用户的一条会话：对方（单聊为 UID，群聊为群 ID）及最后一条消息。
type ConversationSummary struct {
	Peer         string         `json:"peer"`
	ChatType     int32          `json:"chat_type"`
	Title        string         `json:"title"` // 对方昵称 / 群名，查不到时为空
	MessageCount int            `json:"message_count"`
	LastMessage  *proto.Message `json:"last_message"`
}

// AdminConversationStore 是 MessageStore 的可选管理员扩展，调用方先做类型断言。
type AdminConversationStore interface {
	// UserConversations 返回 uid 参与过的会话（单聊 + 发过言或所在的群），按最后消息时间从新到旧。
	UserConversations(ctx context.Context, uid string, limit int) ([]*ConversationSummary, error)
}

// BatchMessageStore 是 MessageStore 的可选批量写扩展。
// 调用方应先做类型断言；不支持时回退到逐条 Save。
type BatchMessageStore interface {
	SaveBatch(ctx context.Context, messages []*proto.Message) error
}
