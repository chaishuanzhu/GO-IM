package gateway

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/tls"
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"log"
	"mime"
	"net"
	"net/mail"
	"net/smtp"
	"strconv"
	"strings"
	"time"

	"github.com/im/configs"
)

// Mailer 发送一封同时带纯文本与 HTML 正文的邮件。
type Mailer interface {
	Send(ctx context.Context, to, subject, text, html string) error
}

// LogMailer 只把邮件写进日志，供未配置 SMTP 的开发环境使用。
type LogMailer struct{}

func (LogMailer) Send(_ context.Context, to, subject, text, _ string) error {
	log.Printf("[mail] (not sent, SMTP disabled) to=%s subject=%q\n%s", to, subject, text)
	return nil
}

// SMTPMailer 通过 SMTP 发送邮件：465 端口使用隐式 TLS，其他端口在服务器支持时升级 STARTTLS。
type SMTPMailer struct {
	host     string
	port     int
	username string
	password string
	from     *mail.Address
}

// NewSMTPMailer 校验配置并创建 SMTPMailer。
func NewSMTPMailer(cfg configs.MailConfig) (*SMTPMailer, error) {
	if cfg.Host == "" || cfg.Port == 0 || cfg.Username == "" {
		return nil, fmt.Errorf("mail: host, port and username are required")
	}
	fromStr := cfg.From
	if fromStr == "" {
		fromStr = cfg.Username
	}
	from, err := mail.ParseAddress(fromStr)
	if err != nil {
		return nil, fmt.Errorf("mail: invalid from %q: %w", fromStr, err)
	}
	return &SMTPMailer{host: cfg.Host, port: cfg.Port, username: cfg.Username, password: cfg.Password, from: from}, nil
}

func (m *SMTPMailer) Send(ctx context.Context, to, subject, text, html string) error {
	addr := net.JoinHostPort(m.host, strconv.Itoa(m.port))
	dialer := &net.Dialer{Timeout: 10 * time.Second}
	var conn net.Conn
	var err error
	if m.port == 465 {
		conn, err = tls.DialWithDialer(dialer, "tcp", addr, &tls.Config{ServerName: m.host})
	} else {
		conn, err = dialer.DialContext(ctx, "tcp", addr)
	}
	if err != nil {
		return fmt.Errorf("smtp dial: %w", err)
	}
	if deadline, ok := ctx.Deadline(); ok {
		_ = conn.SetDeadline(deadline)
	}
	c, err := smtp.NewClient(conn, m.host)
	if err != nil {
		conn.Close()
		return fmt.Errorf("smtp handshake: %w", err)
	}
	defer c.Close()

	if m.port != 465 {
		if ok, _ := c.Extension("STARTTLS"); ok {
			if err := c.StartTLS(&tls.Config{ServerName: m.host}); err != nil {
				return fmt.Errorf("smtp starttls: %w", err)
			}
		}
	}
	if m.password != "" {
		if err := c.Auth(smtp.PlainAuth("", m.username, m.password, m.host)); err != nil {
			return fmt.Errorf("smtp auth: %w", err)
		}
	}
	if err := c.Mail(m.from.Address); err != nil {
		return fmt.Errorf("smtp mail from: %w", err)
	}
	if err := c.Rcpt(to); err != nil {
		return fmt.Errorf("smtp rcpt: %w", err)
	}
	w, err := c.Data()
	if err != nil {
		return fmt.Errorf("smtp data: %w", err)
	}
	if _, err := w.Write(buildMessage(m.from, to, subject, text, html)); err != nil {
		return fmt.Errorf("smtp write: %w", err)
	}
	if err := w.Close(); err != nil {
		return fmt.Errorf("smtp send: %w", err)
	}
	return c.Quit()
}

// buildMessage 生成 multipart/alternative 邮件，标题与正文均以 UTF-8 编码。
func buildMessage(from *mail.Address, to, subject, text, html string) []byte {
	boundary := randomHex(12)
	var b bytes.Buffer
	header := func(k, v string) { fmt.Fprintf(&b, "%s: %s\r\n", k, v) }
	header("From", from.String())
	header("To", to)
	header("Subject", mime.BEncoding.Encode("UTF-8", subject))
	header("Date", time.Now().Format(time.RFC1123Z))
	header("Message-ID", fmt.Sprintf("<%s@%s>", randomHex(16), domainOf(from.Address)))
	header("MIME-Version", "1.0")
	header("Content-Type", `multipart/alternative; boundary="`+boundary+`"`)
	b.WriteString("\r\n")
	for _, part := range []struct{ typ, body string }{{"text/plain", text}, {"text/html", html}} {
		fmt.Fprintf(&b, "--%s\r\nContent-Type: %s; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n", boundary, part.typ)
		enc := base64.StdEncoding.EncodeToString([]byte(part.body))
		for len(enc) > 76 {
			b.WriteString(enc[:76] + "\r\n")
			enc = enc[76:]
		}
		b.WriteString(enc + "\r\n")
	}
	fmt.Fprintf(&b, "--%s--\r\n", boundary)
	return b.Bytes()
}

func domainOf(addr string) string {
	if i := strings.LastIndexByte(addr, '@'); i >= 0 {
		return addr[i+1:]
	}
	return "localhost"
}

func randomHex(n int) string {
	buf := make([]byte, n)
	_, _ = rand.Read(buf)
	return hex.EncodeToString(buf)
}
