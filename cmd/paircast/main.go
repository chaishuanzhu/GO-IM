// Paircast 服务 —— 一起看业务后端：媒体库（/media/*）与观影房间（/room/*）。
// 它作为 IM 的一个接入方运行：用 IM 令牌认证用户，通过服务端 API 建群、拉人、发房间信令，
// 通过 webhook 同步 IM 侧的退群与解散。
//
// 用法：
//
//	CONFIG_PATH=configs/paircast.json PAIRCAST_IM_SECRET=... go run ./cmd/paircast/
package main

import (
	"context"
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"errors"
	"log"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	_ "github.com/go-sql-driver/mysql"

	"github.com/im/internal/paircast"
	"github.com/im/internal/paircast/media"
	"github.com/im/internal/paircast/media/s3source"
	"github.com/im/internal/paircast/watchroom"
	"github.com/im/pkg/imserver"
)

func main() {
	slog.SetDefault(slog.New(slog.NewJSONHandler(os.Stdout, nil)))
	log.SetOutput(os.Stdout)

	configPath := os.Getenv("CONFIG_PATH")
	if configPath == "" {
		configPath = "configs/paircast.json"
	}
	cfg, err := loadConfig(configPath)
	if err != nil {
		log.Fatalf("[paircast] config: %v", err)
	}

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	var db *sql.DB
	if cfg.MySQL.DSN != "" {
		db, err = openMySQL(ctx, cfg.MySQL.DSN)
		if err != nil {
			log.Fatalf("[paircast] mysql: %v", err)
		}
		defer db.Close()
	} else {
		log.Printf("[paircast] WARNING: mysql.dsn is empty, rooms are kept in memory")
	}

	var blobs media.BlobStore
	if cfg.BlobStore.Endpoint != "" {
		api, err := s3source.NewMinioAPI(cfg.BlobStore)
		if err != nil {
			log.Fatalf("[paircast] blob_store: %v", err)
		}
		blobs = api.Blobs(cfg.BlobPrefix)
	} else {
		log.Printf("[paircast] blob_store not configured, shared / online subtitles disabled")
	}

	factories := map[string]media.SourceFactory{"s3": s3source.Factory}
	svc, err := media.NewService(cfg.Media, factories, db, blobs, newBlobID)
	if err != nil {
		log.Fatalf("[paircast] media: %v", err)
	}

	var store watchroom.Store = watchroom.NewMemoryStore()
	if db != nil {
		if store, err = watchroom.NewMySQLStore(db); err != nil {
			log.Fatalf("[paircast] room store: %v", err)
		}
	}
	im := imserver.New(cfg.IM.BaseURL, cfg.IM.AppID, cfg.IM.Secret)
	rooms := watchroom.NewService(store, paircast.IMGroups{C: im}, paircast.IMNotifier{C: im}, watchroom.NewRandomID)
	rooms.StartJanitor(ctx, time.Duration(cfg.Media.RoomRetention), time.Hour)

	mux := http.NewServeMux()
	paircast.NewServer(svc, rooms, paircast.NewIMAuth(im)).Register(mux)
	if cfg.IM.WebhookSecret != "" {
		mux.Handle("/im/webhook", paircast.WebhookHandler(rooms, cfg.IM.WebhookSecret))
	} else {
		log.Printf("[paircast] WARNING: im.webhook_secret is empty, IM-side leaves / dismissals are not synced")
	}
	mux.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		if db != nil {
			if err := db.PingContext(r.Context()); err != nil {
				http.Error(w, "mysql unavailable", http.StatusServiceUnavailable)
				return
			}
		}
		w.Write([]byte("ok"))
	})

	srv := &http.Server{
		Addr:              cfg.HTTPAddr,
		Handler:           mux,
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       60 * time.Second,
		WriteTimeout:      60 * time.Second,
		IdleTimeout:       120 * time.Second,
	}
	go func() {
		<-ctx.Done()
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
		defer cancel()
		srv.Shutdown(shutdownCtx)
	}()
	log.Printf("[paircast] listening on %s (im app %s)", cfg.HTTPAddr, cfg.IM.AppID)
	if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		log.Fatalf("[paircast] http: %v", err)
	}
	log.Printf("[paircast] stopped")
}

// openMySQL 连接 MySQL，启动时数据库可能还没就绪，最多重试 60 秒。
func openMySQL(ctx context.Context, dsn string) (*sql.DB, error) {
	db, err := sql.Open("mysql", dsn)
	if err != nil {
		return nil, err
	}
	deadline := time.Now().Add(60 * time.Second)
	for {
		pingCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
		err = db.PingContext(pingCtx)
		cancel()
		if err == nil {
			return db, nil
		}
		if time.Now().After(deadline) || ctx.Err() != nil {
			db.Close()
			return nil, err
		}
		log.Printf("[paircast] mysql not ready: %v, retrying", err)
		time.Sleep(3 * time.Second)
	}
}

func newBlobID() string {
	b := make([]byte, 12)
	rand.Read(b)
	return hex.EncodeToString(b)
}
