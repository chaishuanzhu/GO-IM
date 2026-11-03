package main

import (
	"encoding/json"
	"errors"
	"log"
	"os"
	"time"

	"github.com/im/configs"
)

// Config 是 Paircast 服务的配置（JSON，路径由 CONFIG_PATH 指定，默认 configs/paircast.json）。
// 密钥只建议用环境变量提供：PAIRCAST_IM_SECRET、PAIRCAST_IM_WEBHOOK_SECRET、PAIRCAST_MYSQL_DSN、
// PAIRCAST_BLOB_ACCESS_KEY / PAIRCAST_BLOB_SECRET_KEY，以及媒体库的 GOIM_MEDIA_<ID>_* / GOIM_TMDB_ACCESS_TOKEN / GOIM_OPENSUBTITLES_API_KEY。
type Config struct {
	HTTPAddr string `json:"http_addr"` // 默认 ":8090"

	// IM 服务端 API（/server/v1/*）与 webhook。Paircast 在 IM 侧登记为一个接入方，
	// webhook_url 指向本服务的 /im/webhook，订阅 group.member_left 与 group.dismissed。
	IM struct {
		BaseURL       string `json:"base_url"` // 例如 "http://gateway:8080"
		AppID         string `json:"app_id"`
		Secret        string `json:"secret"`
		WebhookSecret string `json:"webhook_secret"`
	} `json:"im"`

	// MySQL 保存房间与刮削缓存；DSN 为空时都在内存中（重启丢失）。
	MySQL struct {
		DSN string `json:"dsn"`
	} `json:"mysql"`

	Media configs.MediaConfig `json:"media"`

	// 共享 / 在线下载的字幕所在的 S3 兼容存储；endpoint 为空时不提供字幕共享与在线字幕下载。
	BlobStore  configs.MediaS3Config `json:"blob_store"`
	BlobPrefix string                `json:"blob_prefix"` // 对象键前缀（键本身以 "paircast/subtitles/" 开头），默认为空
}

func loadConfig(path string) (*Config, error) {
	cfg := &Config{HTTPAddr: ":8090", Media: configs.DefaultMedia()}
	data, err := os.ReadFile(path)
	switch {
	case os.IsNotExist(err):
		log.Printf("[paircast] no config file at %s, using defaults + env", path)
	case err != nil:
		return nil, err
	default:
		if err := json.Unmarshal(data, cfg); err != nil {
			return nil, err
		}
	}
	envOverride(&cfg.IM.BaseURL, "PAIRCAST_IM_BASE_URL")
	envOverride(&cfg.IM.AppID, "PAIRCAST_IM_APP_ID")
	envOverride(&cfg.IM.Secret, "PAIRCAST_IM_SECRET")
	envOverride(&cfg.IM.WebhookSecret, "PAIRCAST_IM_WEBHOOK_SECRET")
	envOverride(&cfg.MySQL.DSN, "PAIRCAST_MYSQL_DSN")
	envOverride(&cfg.BlobStore.AccessKey, "PAIRCAST_BLOB_ACCESS_KEY")
	envOverride(&cfg.BlobStore.SecretKey, "PAIRCAST_BLOB_SECRET_KEY")
	configs.ApplyMediaEnv(&cfg.Media)

	if cfg.IM.BaseURL == "" || cfg.IM.AppID == "" || cfg.IM.Secret == "" {
		return nil, errors.New("im.base_url, im.app_id and im.secret (PAIRCAST_IM_SECRET) are required")
	}
	if len(cfg.Media.Sources) == 0 {
		return nil, errors.New("media.sources is empty")
	}
	if time.Duration(cfg.Media.RoomRetention) <= 0 {
		cfg.Media.RoomRetention = configs.DefaultMedia().RoomRetention
	}
	return cfg, nil
}

func envOverride(dst *string, key string) {
	if v := os.Getenv(key); v != "" {
		*dst = v
	}
}
