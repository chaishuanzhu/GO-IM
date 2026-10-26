package gateway

import (
	"context"
	"log"

	"github.com/im/api/proto"
)

// CmdKick 的内容：账号被管理员禁用 / 删除。客户端据此提示原因并停止重连。
const (
	kickAccountDisabled = "account disabled"
	kickAccountDeleted  = "account deleted"
)

// accountBlocked 返回 uid 不能建立长连接的原因（CmdKick 内容），可以连接时返回空串。
// 已签发的 JWT 在过期前一直有效，所以连接时要再查一次账号状态：被禁用的不行；
// 非 dev_mode 下已删除的也不行（dev_mode 登录不建用户记录）。
// 没有用户存储或查询失败时放行，避免数据库故障把所有人挡在外面。
func (s *Server) accountBlocked(ctx context.Context, uid string) string {
	if s.userStore == nil {
		return ""
	}
	u, err := s.userStore.GetByUID(ctx, uid)
	if err != nil {
		log.Printf("[server] account check %s: %v", uid, err)
		return ""
	}
	switch {
	case u == nil && !s.authCfg.DevMode:
		return kickAccountDeleted
	case u != nil && u.IsDisabled:
		return kickAccountDisabled
	}
	return ""
}

// kickUser 通知 uid 在本节点的所有连接下线并关闭。其他网关上的连接不受影响，
// 但它们重连时会被 accountBlocked 拒绝。
func (s *Server) kickUser(ctx context.Context, uid, reason string) int {
	conns := s.clients.GetAll(ctx, uid)
	for _, c := range conns {
		c.Send(&proto.Message{Cmd: proto.CmdKick, Content: reason})
		c.Close()
	}
	if len(conns) > 0 {
		log.Printf("[server] kicked %d connection(s) of %s: %s", len(conns), uid, reason)
	}
	return len(conns)
}
