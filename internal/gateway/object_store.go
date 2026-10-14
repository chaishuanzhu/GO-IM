package gateway

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/im/configs"
	"github.com/minio/minio-go/v7"
	"github.com/minio/minio-go/v7/pkg/cors"
	"github.com/minio/minio-go/v7/pkg/credentials"
)

// ObjectStore 是文件/对象存储的接口(MinIO/S3 或内存)。
type ObjectStore interface {
	// Put 以给定内容类型将数据存储到 key 下。
	Put(ctx context.Context, key string, data []byte, contentType string) error

	// Get 检索 key 对应的数据及内容类型。
	// 如果 key 不存在则返回错误。
	Get(ctx context.Context, key string) (data []byte, contentType string, err error)

	// Delete 从存储中移除一个 key。
	Delete(ctx context.Context, key string) error
}

// storedObject 在内存中保存文件数据。
type storedObject struct {
	data        []byte
	contentType string
}

// InMemoryObjectStore 是用于测试和开发的内存版 ObjectStore。
// 重启后数据丢失。
type InMemoryObjectStore struct {
	mu    sync.RWMutex
	items map[string]*storedObject
}

// NewInMemoryObjectStore 创建一个空的 InMemoryObjectStore。
func NewInMemoryObjectStore() *InMemoryObjectStore {
	return &InMemoryObjectStore{
		items: make(map[string]*storedObject),
	}
}

// Put 将数据存入内存。
func (s *InMemoryObjectStore) Put(_ context.Context, key string, data []byte, contentType string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	// 复制数据,使调用方可以复用该切片。
	cp := make([]byte, len(data))
	copy(cp, data)

	s.items[key] = &storedObject{
		data:        cp,
		contentType: contentType,
	}
	return nil
}

// Get 从内存中检索数据。
func (s *InMemoryObjectStore) Get(_ context.Context, key string) ([]byte, string, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	obj, ok := s.items[key]
	if !ok {
		return nil, "", fmt.Errorf("key %q not found", key)
	}

	// 返回副本,防止调用方修改内部状态。
	cp := make([]byte, len(obj.data))
	copy(cp, obj.data)
	return cp, obj.contentType, nil
}

// Delete 从内存中移除一个 key。
func (s *InMemoryObjectStore) Delete(_ context.Context, key string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	delete(s.items, key)
	return nil
}

// DirectUploader 由支持客户端直传的对象存储实现：签发预签名 URL，并在确认上传时查询对象。
type DirectUploader interface {
	// PresignPut 返回上传 URL 及客户端必须原样携带的请求头（大小与类型参与签名）。
	PresignPut(ctx context.Context, key string, size int64, contentType string, ttl time.Duration) (string, http.Header, error)
	// PresignGet 返回下载 URL；filename 非空时以附件名下载。
	PresignGet(ctx context.Context, key, filename string, ttl time.Duration) (string, error)
	Stat(ctx context.Context, key string) (size int64, contentType string, err error)
	// ReadHead 读取对象开头至多 n 字节。
	ReadHead(ctx context.Context, key string, n int64) ([]byte, error)
}

// MinioStore 是基于 MinIO/S3 的 ObjectStore。
type MinioStore struct {
	client *minio.Client
	bucket string
	// public 只用于按客户端可访问的地址签名，不发起网络请求；未配置 public_endpoint 时为 nil。
	public *minio.Client
}

var _ DirectUploader = (*MinioStore)(nil)

// NewMinioStore 创建一个 MinioStore 并确保桶存在。
func NewMinioStore(ctx context.Context, cfg configs.ObjectStorageConfig) (*MinioStore, error) {
	region := cfg.Region
	if region == "" {
		region = "us-east-1"
	}
	creds := credentials.NewStaticV4(cfg.AccessKey, cfg.SecretKey, "")
	client, err := minio.New(cfg.Endpoint, &minio.Options{
		Creds:  creds,
		Secure: cfg.UseSSL,
		Region: region,
	})
	if err != nil {
		return nil, fmt.Errorf("minio client: %w", err)
	}

	var public *minio.Client
	if cfg.PublicEndpoint != "" {
		host, secure, err := parsePublicEndpoint(cfg.PublicEndpoint, cfg.UseSSL)
		if err != nil {
			return nil, err
		}
		public, err = minio.New(host, &minio.Options{Creds: creds, Secure: secure, Region: region})
		if err != nil {
			return nil, fmt.Errorf("minio public client: %w", err)
		}
	}

	bucket := cfg.Bucket
	if bucket == "" {
		bucket = "im-files"
	}

	// 确保桶存在。
	exists, err := client.BucketExists(ctx, bucket)
	if err != nil {
		return nil, fmt.Errorf("minio bucket check: %w", err)
	}
	if !exists {
		if err := client.MakeBucket(ctx, bucket, minio.MakeBucketOptions{}); err != nil {
			return nil, fmt.Errorf("minio make bucket: %w", err)
		}
		log.Printf("[minio] created bucket %q", bucket)
	}

	if len(cfg.CORSAllowedOrigins) > 0 {
		rules := cors.NewConfig([]cors.Rule{{
			AllowedOrigin: cfg.CORSAllowedOrigins,
			AllowedMethod: []string{http.MethodGet, http.MethodPut, http.MethodHead},
			AllowedHeader: []string{"*"},
			ExposeHeader:  []string{"ETag", "Content-Length", "Content-Range", "Content-Disposition"},
			MaxAgeSeconds: 3600,
		}})
		if err := client.SetBucketCors(ctx, bucket, rules); err != nil {
			log.Printf("[minio] set bucket CORS failed (browser direct upload will fail): %v", err)
		}
	}

	log.Printf("[minio] connected to %s, bucket=%s, public endpoint=%q", cfg.Endpoint, bucket, cfg.PublicEndpoint)
	return &MinioStore{client: client, bucket: bucket, public: public}, nil
}

// parsePublicEndpoint 接受 "https://oss.example.com"、"http://1.2.3.4:9000" 或不带协议的 "host:port"。
func parsePublicEndpoint(raw string, defaultSecure bool) (host string, secure bool, err error) {
	if !strings.Contains(raw, "://") {
		return strings.TrimSuffix(raw, "/"), defaultSecure, nil
	}
	u, err := url.Parse(raw)
	if err != nil {
		return "", false, fmt.Errorf("public_endpoint: %w", err)
	}
	if (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" || strings.Trim(u.Path, "/") != "" {
		return "", false, fmt.Errorf("public_endpoint %q: want scheme://host[:port] without path", raw)
	}
	return u.Host, u.Scheme == "https", nil
}

// DirectEnabled 报告是否配置了 public_endpoint，即能否签发直传 URL。
func (s *MinioStore) DirectEnabled() bool {
	return s.public != nil
}

// PresignPut 签发上传 URL；Content-Length 与 Content-Type 参与签名，客户端发送的值不一致会被拒绝。
func (s *MinioStore) PresignPut(ctx context.Context, key string, size int64, contentType string, ttl time.Duration) (string, http.Header, error) {
	headers := http.Header{}
	headers.Set("Content-Type", contentType)
	headers.Set("Content-Length", strconv.FormatInt(size, 10))
	u, err := s.public.PresignHeader(ctx, http.MethodPut, s.bucket, key, ttl, nil, headers)
	if err != nil {
		return "", nil, fmt.Errorf("presign put %q: %w", key, err)
	}
	return u.String(), headers, nil
}

// PresignGet 签发下载 URL，响应可被浏览器私有缓存到 URL 过期前。
func (s *MinioStore) PresignGet(ctx context.Context, key, filename string, ttl time.Duration) (string, error) {
	params := url.Values{}
	params.Set("response-cache-control", fmt.Sprintf("private, max-age=%d", int(ttl.Seconds())))
	if filename != "" {
		params.Set("response-content-disposition", "attachment; filename*=UTF-8''"+url.PathEscape(filename))
	}
	u, err := s.public.PresignedGetObject(ctx, s.bucket, key, ttl, params)
	if err != nil {
		return "", fmt.Errorf("presign get %q: %w", key, err)
	}
	return u.String(), nil
}

// Stat 返回对象大小与类型。
func (s *MinioStore) Stat(ctx context.Context, key string) (int64, string, error) {
	info, err := s.client.StatObject(ctx, s.bucket, key, minio.StatObjectOptions{})
	if err != nil {
		return 0, "", fmt.Errorf("minio stat %q: %w", key, err)
	}
	return info.Size, info.ContentType, nil
}

// ReadHead 读取对象开头至多 n 字节。
func (s *MinioStore) ReadHead(ctx context.Context, key string, n int64) ([]byte, error) {
	opts := minio.GetObjectOptions{}
	if err := opts.SetRange(0, n-1); err != nil {
		return nil, err
	}
	obj, err := s.client.GetObject(ctx, s.bucket, key, opts)
	if err != nil {
		return nil, fmt.Errorf("minio get %q: %w", key, err)
	}
	defer obj.Close()
	data, err := io.ReadAll(io.LimitReader(obj, n))
	if err != nil {
		return nil, fmt.Errorf("minio read %q: %w", key, err)
	}
	return data, nil
}

// Put 将数据上传到 MinIO。
func (s *MinioStore) Put(ctx context.Context, key string, data []byte, contentType string) error {
	reader := bytes.NewReader(data)
	_, err := s.client.PutObject(ctx, s.bucket, key, reader, int64(len(data)), minio.PutObjectOptions{
		ContentType: contentType,
	})
	if err != nil {
		return fmt.Errorf("minio put %q: %w", key, err)
	}
	return nil
}

// Get 从 MinIO 下载数据。
func (s *MinioStore) Get(ctx context.Context, key string) ([]byte, string, error) {
	obj, err := s.client.GetObject(ctx, s.bucket, key, minio.GetObjectOptions{})
	if err != nil {
		return nil, "", fmt.Errorf("minio get %q: %w", key, err)
	}
	defer obj.Close()

	stat, err := obj.Stat()
	if err != nil {
		return nil, "", fmt.Errorf("minio stat %q: %w", key, err)
	}

	data, err := io.ReadAll(obj)
	if err != nil {
		return nil, "", fmt.Errorf("minio read %q: %w", key, err)
	}

	return data, stat.ContentType, nil
}

// Ping 通过验证桶是否存在来检查 MinIO 连通性。
func (s *MinioStore) Ping(ctx context.Context) error {
	_, err := s.client.BucketExists(ctx, s.bucket)
	return err
}

// Delete 从 MinIO 中移除一个对象。
func (s *MinioStore) Delete(ctx context.Context, key string) error {
	err := s.client.RemoveObject(ctx, s.bucket, key, minio.RemoveObjectOptions{})
	if err != nil {
		return fmt.Errorf("minio delete %q: %w", key, err)
	}
	return nil
}
