package gateway

import (
	"context"
	"crypto/rand"
	"errors"
	"fmt"
	"html"
	"log"
	"math/big"
	"time"

	"golang.org/x/crypto/bcrypt"

	"github.com/im/internal/repo"
)

// 不含 0/O、1/l/I 等易混字符，方便照着邮件手输。
const bootstrapPasswordAlphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"

const bootstrapPasswordLen = 16

// BootstrapAdmin 确保 email 对应的账号存在且为管理员。
// 账号不存在时以随机密码创建（跳过邮箱验证码，首次登录须改密码），并把密码发到该邮箱；
// mailer 为 nil 或发送失败时把密码写进日志，否则密码无处可取（找回密码同样依赖邮件）。
// 已存在的账号只提升角色，不动密码。多个网关同时启动时只有抢到插入的那个会建号发信。
func BootstrapAdmin(ctx context.Context, users repo.UserStore, mailer Mailer, rawEmail string) error {
	email, ok := normalizeEmail(rawEmail)
	if !ok {
		return fmt.Errorf("invalid admin_email %q", rawEmail)
	}
	existing, err := users.GetByEmail(ctx, email)
	if err != nil {
		return fmt.Errorf("lookup admin: %w", err)
	}
	if existing != nil {
		if existing.Role == "admin" {
			return nil
		}
		if err := users.UpdateUserRole(ctx, existing.UID, "admin"); err != nil {
			return fmt.Errorf("promote admin %s: %w", existing.UID, err)
		}
		log.Printf("[admin] existing account %s (%s) promoted to admin", existing.UID, maskEmail(email))
		return nil
	}

	password, err := randomPassword(bootstrapPasswordLen)
	if err != nil {
		return fmt.Errorf("generate password: %w", err)
	}
	hash, err := bcrypt.GenerateFromPassword([]byte(password), bcrypt.DefaultCost)
	if err != nil {
		return fmt.Errorf("hash password: %w", err)
	}
	now := time.Now().UnixMilli()
	u := &repo.User{
		Nickname:           defaultNickname(email),
		Email:              email,
		PasswordHash:       string(hash),
		Role:               "admin",
		MustChangePassword: true,
		CreatedAt:          now,
		UpdatedAt:          now,
	}
	if err := users.CreateWithAutoUID(ctx, u); err != nil {
		if errors.Is(err, repo.ErrEmailTaken) {
			return nil
		}
		return fmt.Errorf("create admin: %w", err)
	}
	log.Printf("[admin] created admin account uid=%s email=%s", u.UID, maskEmail(email))

	if mailer != nil {
		subject, text, body := adminAccountMail(u.UID, email, password)
		sendCtx, cancel := context.WithTimeout(ctx, mailSendTimeout)
		err = mailer.Send(sendCtx, email, subject, text, body)
		cancel()
		if err == nil {
			log.Printf("[admin] initial password sent to %s", maskEmail(email))
			return nil
		}
		log.Printf("[admin] WARNING: sending initial password to %s failed: %v", maskEmail(email), err)
	}
	log.Printf("[admin] WARNING: initial password for %s (uid=%s) could not be emailed: %s — log in and change it now",
		email, u.UID, password)
	return nil
}

func randomPassword(n int) (string, error) {
	max := big.NewInt(int64(len(bootstrapPasswordAlphabet)))
	out := make([]byte, n)
	for i := range out {
		idx, err := rand.Int(rand.Reader, max)
		if err != nil {
			return "", err
		}
		out[i] = bootstrapPasswordAlphabet[idx.Int64()]
	}
	return string(out), nil
}

func adminAccountMail(uid, email, password string) (subject, text, body string) {
	subject = "GO-IM 管理员账号已创建"
	text = "GO-IM 已为你创建管理员账号。\n\n" +
		"登录账号：" + email + "（或用户 ID " + uid + "）\n" +
		"初始密码：" + password + "\n\n" +
		"首次登录后需要先设置新密码。请妥善保管本邮件，改密后可删除。\n"
	body = `<div style="font-family:-apple-system,Segoe UI,Helvetica,Arial,sans-serif;max-width:480px;margin:0 auto;padding:24px;color:#1f2329">` +
		`<h2 style="margin:0 0 16px;font-size:18px">GO-IM</h2>` +
		`<p style="margin:0 0 12px">已为你创建管理员账号。</p>` +
		`<p style="margin:0 0 4px">登录账号：` + html.EscapeString(email) + `（或用户 ID ` + html.EscapeString(uid) + `）</p>` +
		`<p style="margin:0 0 4px">初始密码：</p>` +
		`<p style="margin:0 0 16px;font-size:22px;font-weight:600;font-family:Menlo,Consolas,monospace;letter-spacing:2px">` + html.EscapeString(password) + `</p>` +
		`<p style="margin:0;color:#8f959e;font-size:13px">首次登录后需要先设置新密码。请妥善保管本邮件，改密后可删除。</p>` +
		`</div>`
	return
}
