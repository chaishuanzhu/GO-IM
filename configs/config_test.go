package configs

import (
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestDefaultBatchPersistenceConfig(t *testing.T) {
	cfg := Default()
	g := cfg.Gateway
	if g.PersistQueueSize != 8192 ||
		g.PersistBatchSize != 200 ||
		time.Duration(g.PersistFlushInterval) != 10*time.Millisecond ||
		g.PersistBatchWorkers != 2 ||
		time.Duration(g.PersistEnqueueTimeout) != 10*time.Millisecond ||
		g.PersistMaxRetries != 3 {
		t.Fatalf("unexpected persistence defaults: %+v", g)
	}
	if g.MySQL.MaxOpenConns != 32 || g.MySQL.MaxIdleConns != 16 {
		t.Fatalf("unexpected MySQL pool defaults: %+v", g.MySQL)
	}
}

func TestLoadLegacyConfigKeepsBatchDefaults(t *testing.T) {
	path := filepath.Join(t.TempDir(), "config.json")
	if err := os.WriteFile(path, []byte(`{
		"gateway": {
			"http_addr": ":18080",
			"persist_concurrency": 64,
			"mysql": {"enabled": false}
		}
	}`), 0o600); err != nil {
		t.Fatal(err)
	}
	cfg, err := Load(path)
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Gateway.PersistQueueSize != 8192 ||
		cfg.Gateway.PersistBatchSize != 200 ||
		cfg.Gateway.PersistBatchWorkers != 2 {
		t.Fatalf("legacy config lost batch defaults: %+v", cfg.Gateway)
	}
}
