package webhook

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"log"
	"net/http"
	"strconv"
	"time"
)

const (
	// MaxRetryAge 是从首次入队算起的最长重试时间，超过即进入死信。
	MaxRetryAge = 24 * time.Hour
	maxBackoff  = time.Hour
	httpTimeout = 5 * time.Second
)

// backoff 返回第 attempt 次失败后的等待时间：2^attempt 秒，封顶 1 小时。
func backoff(attempt int) time.Duration {
	if attempt > 12 {
		return maxBackoff
	}
	d := time.Duration(1<<attempt) * time.Second
	return min(d, maxBackoff)
}

// Dispatcher 为每个接入方运行一个投递协程。
type Dispatcher struct {
	targets []Target
	queue   Queue
	http    *http.Client
	now     func() time.Time
	poll    time.Duration
}

// NewDispatcher 创建分发器。
func NewDispatcher(targets []Target, queue Queue) *Dispatcher {
	return &Dispatcher{targets: targets, queue: queue, http: &http.Client{Timeout: httpTimeout}, now: time.Now, poll: time.Second}
}

// Start 启动投递协程，直到 ctx 结束。
func (d *Dispatcher) Start(ctx context.Context) {
	for _, t := range d.targets {
		if t.URL == "" {
			continue
		}
		go d.run(ctx, t)
	}
}

func (d *Dispatcher) run(ctx context.Context, t Target) {
	for ctx.Err() == nil {
		due, err := d.queue.Due(ctx, t.AppID, d.now())
		if err != nil {
			log.Printf("[webhook] %s: read retries: %v", t.AppID, err)
		}
		for _, it := range due {
			d.deliver(ctx, t, it)
		}
		fresh, err := d.queue.Next(ctx, t.AppID, d.poll)
		if err != nil && ctx.Err() == nil {
			log.Printf("[webhook] %s: read queue: %v", t.AppID, err)
			select {
			case <-ctx.Done():
			case <-time.After(d.poll):
			}
			continue
		}
		for _, it := range fresh {
			d.deliver(ctx, t, it)
		}
	}
}

func (d *Dispatcher) deliver(ctx context.Context, t Target, it delivery) {
	err := d.post(ctx, t, it)
	if err == nil {
		if err := d.queue.Done(ctx, t.AppID, it); err != nil {
			log.Printf("[webhook] %s: ack %s: %v", t.AppID, it.ID, err)
		}
		return
	}
	it.Attempt++
	now := d.now()
	next := now.Add(backoff(it.Attempt))
	if next.Sub(time.UnixMilli(it.First)) > MaxRetryAge {
		log.Printf("[webhook] %s: %s %s dead after %d attempts: %v", t.AppID, it.Type, it.ID, it.Attempt, err)
		d.queue.Dead(ctx, t.AppID, it)
		return
	}
	if it.Attempt == 1 || it.Attempt%5 == 0 {
		log.Printf("[webhook] %s: %s %s attempt %d failed: %v", t.AppID, it.Type, it.ID, it.Attempt, err)
	}
	if err := d.queue.Retry(ctx, t.AppID, it, next); err != nil {
		log.Printf("[webhook] %s: schedule retry %s: %v", t.AppID, it.ID, err)
	}
}

func (d *Dispatcher) post(ctx context.Context, t Target, it delivery) error {
	ts := strconv.FormatInt(d.now().Unix(), 10)
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, t.URL, bytes.NewReader(it.Body))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set(HeaderEvent, it.Type)
	req.Header.Set(HeaderDelivery, it.ID)
	req.Header.Set(HeaderTimestamp, ts)
	req.Header.Set(HeaderSignature, Sign(t.Secret, ts, it.Body))
	resp, err := d.http.Do(req)
	if err != nil {
		return err
	}
	io.Copy(io.Discard, io.LimitReader(resp.Body, 64<<10))
	resp.Body.Close()
	if resp.StatusCode/100 != 2 {
		return fmt.Errorf("status %d", resp.StatusCode)
	}
	return nil
}
