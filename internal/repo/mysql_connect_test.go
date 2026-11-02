package repo

import (
	"strings"
	"testing"
	"time"
)

func TestConnectMySQLWithRetryGivesUp(t *testing.T) {
	old := mysqlRetryInterval
	mysqlRetryInterval = 20 * time.Millisecond
	defer func() { mysqlRetryInterval = old }()

	// 端口 1 上没有服务，连接会被立即拒绝。
	dsn := "im:x@tcp(127.0.0.1:1)/im?timeout=200ms"
	start := time.Now()
	s, err := ConnectMySQLWithRetry(dsn, DefaultMySQLPoolConfig(), 100*time.Millisecond)
	if err == nil {
		s.Close()
		t.Fatal("expected error")
	}
	if !strings.Contains(err.Error(), "attempts") {
		t.Fatalf("error should report attempts: %v", err)
	}
	if strings.HasPrefix(err.Error(), "after 1 attempts") {
		t.Fatalf("expected retries within timeout: %v", err)
	}
	if time.Since(start) > 2*time.Second {
		t.Fatalf("retry loop overran timeout: %s", time.Since(start))
	}
}

func TestConnectMySQLWithRetryZeroTimeoutTriesOnce(t *testing.T) {
	_, err := ConnectMySQLWithRetry("im:x@tcp(127.0.0.1:1)/im?timeout=200ms", DefaultMySQLPoolConfig(), 0)
	if err == nil || !strings.HasPrefix(err.Error(), "after 1 attempts") {
		t.Fatalf("expected single attempt, got %v", err)
	}
}
