package main

import (
	"context"
	"log"
	"os"
	"time"

	"github.com/im/configs"
	"github.com/im/internal/gateway"
	"github.com/im/internal/repo"
	"github.com/im/internal/serverapi"
	"github.com/im/internal/webhook"
	"github.com/redis/go-redis/v9"
)

// setupServerAPI 在配置了 server_api.apps 时创建服务端 API；需要 MySQL（外部用户映射与令牌版本）。
func setupServerAPI(cfg *configs.Config, mysqlStore *repo.MySQLStore, rdb *redis.Client,
	groups gateway.GroupStore, server *gateway.Server, router *gateway.Router) (*serverapi.API, *serverapi.Registry, error) {
	sc := cfg.Gateway.ServerAPI
	if len(sc.Apps) == 0 {
		return nil, nil, nil
	}
	if mysqlStore == nil {
		log.Printf("[main] server api disabled: it needs MySQL")
		return nil, nil, nil
	}
	apps := serverapi.NewRegistry(sc.Apps)
	if apps.Len() == 0 {
		log.Printf("[main] server api disabled: no app has both app_id and secret")
		return nil, nil, nil
	}
	store, err := serverapi.NewAppStore(mysqlStore.DB())
	if err != nil {
		return nil, nil, err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := apps.AttachStore(ctx, store); err != nil {
		return nil, nil, err
	}
	var nonces serverapi.NonceStore = &serverapi.MemoryNonceStore{}
	if rdb != nil {
		nonces = serverapi.RedisNonceStore{RDB: rdb}
	}
	maxTTL := time.Duration(sc.MaxTokenTTL)
	if maxTTL <= 0 {
		maxTTL = time.Duration(cfg.JWT.Expiration)
	}
	api := serverapi.New(serverapi.Deps{
		Apps:        apps,
		Nonces:      nonces,
		Users:       mysqlStore,
		Groups:      groups,
		Accounts:    server,
		Messenger:   router,
		MaxTokenTTL: maxTTL,
	})
	log.Printf("[main] server api enabled for %d app(s)", apps.Len())
	return api, apps, nil
}

// setupWebhooks 为配置了 webhook_url 的接入方创建事件发布与投递（有 Redis 时用 Redis Stream，多网关共享）。
func setupWebhooks(cfg *configs.Config, rdb *redis.Client) (*webhook.Publisher, *webhook.Dispatcher) {
	var targets []webhook.Target
	for _, a := range cfg.Gateway.ServerAPI.Apps {
		if a.AppID == "" || a.WebhookURL == "" {
			continue
		}
		secret := a.WebhookSecret
		if secret == "" {
			secret = a.Secret
		}
		if secret == "" {
			log.Printf("[main] webhook for %s skipped: no webhook_secret or secret", a.AppID)
			continue
		}
		targets = append(targets, webhook.Target{AppID: a.AppID, URL: a.WebhookURL, Secret: secret, Events: a.WebhookEvents})
	}
	if len(targets) == 0 {
		return nil, nil
	}
	var queue webhook.Queue = webhook.NewMemoryQueue()
	if rdb != nil {
		consumer := cfg.Gateway.Grpc.NodeID
		if consumer == "" {
			consumer, _ = os.Hostname()
		}
		queue = webhook.NewRedisQueue(rdb, consumer)
		log.Printf("[main] webhooks: %d app(s), Redis Stream queue (consumer=%s)", len(targets), consumer)
	} else {
		log.Printf("[main] webhooks: %d app(s), in-memory queue (lost on restart)", len(targets))
	}
	return webhook.NewPublisher(targets, queue), webhook.NewDispatcher(targets, queue)
}

// setupBeforeSend 返回第一个配置了 before_send_url 的接入方的回调。
func setupBeforeSend(cfg *configs.Config) gateway.BeforeSendHook {
	var hook gateway.BeforeSendHook
	for _, a := range cfg.Gateway.ServerAPI.Apps {
		if a.BeforeSendURL == "" {
			continue
		}
		if hook != nil {
			log.Printf("[main] before_send for %s ignored: only one app may set before_send_url", a.AppID)
			continue
		}
		secret := a.WebhookSecret
		if secret == "" {
			secret = a.Secret
		}
		hook = webhook.NewBeforeSend(a.BeforeSendURL, secret, time.Duration(a.BeforeSendTimeout))
		log.Printf("[main] before_send enabled for %s", a.AppID)
	}
	return hook
}
