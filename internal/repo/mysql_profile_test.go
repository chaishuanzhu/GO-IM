package repo

import (
	"context"
	"errors"
	"strconv"
	"testing"
	"time"
)

func TestMySQLUserProfile(t *testing.T) {
	s := newTestMySQLStore(t)
	defer s.Close()
	ctx := context.Background()
	stamp := strconv.FormatInt(time.Now().UnixNano(), 36)
	emailA, emailB := "pa_"+stamp+"@example.com", "pb_"+stamp+"@example.com"
	var created []string
	defer func() {
		for _, uid := range created {
			s.db.Exec(`DELETE FROM users WHERE uid = ?`, uid)
		}
	}()

	now := time.Now().UnixMilli()
	a := &User{Nickname: "Prof_" + stamp + "_甲", Email: "PA_" + stamp + "@Example.com", PasswordHash: "h", Role: "user", CreatedAt: now}
	if err := s.CreateWithAutoUID(ctx, a); err != nil {
		t.Fatalf("create a: %v", err)
	}
	created = append(created, a.UID)
	if n, err := strconv.Atoi(a.UID); err != nil || n < 100000 {
		t.Fatalf("auto uid = %q", a.UID)
	}
	b := &User{Nickname: "Prof_" + stamp + "_乙", Email: emailB, PasswordHash: "h", Role: "user", CreatedAt: now}
	if err := s.CreateWithAutoUID(ctx, b); err != nil {
		t.Fatalf("create b: %v", err)
	}
	created = append(created, b.UID)
	if b.UID == a.UID {
		t.Fatal("duplicate auto uid")
	}

	dup := &User{Nickname: "dup", Email: emailA, PasswordHash: "h", CreatedAt: now}
	if err := s.CreateWithAutoUID(ctx, dup); !errors.Is(err, ErrEmailTaken) {
		t.Fatalf("duplicate email create: %v", err)
	}

	got, err := s.GetByEmail(ctx, emailA)
	if err != nil || got == nil || got.UID != a.UID || got.Email != emailA {
		t.Fatalf("get by email = %+v, %v", got, err)
	}
	if got, _ := s.GetByEmail(ctx, "missing_"+emailA); got != nil {
		t.Fatalf("missing email returned %+v", got)
	}

	if err := s.SetEmail(ctx, b.UID, emailA); !errors.Is(err, ErrEmailTaken) {
		t.Fatalf("set taken email: %v", err)
	}
	if err := s.SetEmail(ctx, "no_such_user_"+stamp, "x_"+emailA); !errors.Is(err, ErrUserNotFound) {
		t.Fatalf("set email unknown user: %v", err)
	}

	nick, sig, avatar := "Renamed_"+stamp, "签名 "+stamp, int64(424242)
	if err := s.UpdateProfile(ctx, a.UID, &nick, &sig, &avatar); err != nil {
		t.Fatalf("update profile: %v", err)
	}
	got, _ = s.GetByUID(ctx, a.UID)
	if got.Nickname != nick || got.Signature != sig || got.AvatarFileID != avatar || got.UpdatedAt == 0 {
		t.Fatalf("after update = %+v", got)
	}
	if err := s.UpdateProfile(ctx, "no_such_user_"+stamp, &nick, nil, nil); !errors.Is(err, ErrUserNotFound) {
		t.Fatalf("update unknown user: %v", err)
	}

	users, err := s.GetUsers(ctx, []string{a.UID, b.UID, "no_such_user_" + stamp})
	if err != nil || len(users) != 2 {
		t.Fatalf("get users = %v, %v", users, err)
	}

	for q, want := range map[string]string{
		"_" + stamp + "_乙": b.UID, // 昵称包含
		emailB:             b.UID, // 邮箱精确
		a.UID:              a.UID, // UID 精确
	} {
		res, err := s.SearchUsers(ctx, q, 10)
		if err != nil || len(res) != 1 || res[0].UID != want {
			t.Fatalf("search %q = %v, %v", q, res, err)
		}
	}
	// LIKE 通配符按字面匹配。
	if res, _ := s.SearchUsers(ctx, "%", 10); len(res) != 0 {
		for _, u := range res {
			if u.UID == a.UID || u.UID == b.UID {
				t.Fatalf("'%%' matched %s", u.UID)
			}
		}
	}
}

func TestRedactDSN(t *testing.T) {
	got := RedactDSN("im:s3cret@tcp(mysql:3306)/im?parseTime=true")
	if got != "im@tcp(mysql:3306)/im" {
		t.Fatalf("RedactDSN = %q", got)
	}
	if RedactDSN("::bad") == "" {
		t.Fatal("empty result for bad dsn")
	}
}
