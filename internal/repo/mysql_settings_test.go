package repo

import (
	"context"
	"strconv"
	"testing"
	"time"
)

func TestMySQLSettings(t *testing.T) {
	s := newTestMySQLStore(t)
	defer s.Close()
	ctx := context.Background()
	key := "test_" + strconv.FormatInt(time.Now().UnixNano(), 36)
	defer s.db.Exec(`DELETE FROM system_settings WHERE k = ?`, key)

	if err := s.PutSettings(ctx, map[string]string{key: "a"}); err != nil {
		t.Fatal(err)
	}
	if err := s.PutSettings(ctx, map[string]string{key: "b"}); err != nil {
		t.Fatal(err)
	}
	got, err := s.GetSettings(ctx)
	if err != nil || got[key] != "b" {
		t.Fatalf("settings = %v, %v", got, err)
	}
}
