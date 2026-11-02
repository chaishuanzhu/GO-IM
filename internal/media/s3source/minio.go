package s3source

import (
	"context"
	"errors"
	"io"
	"net/url"
	"strings"
	"time"

	"github.com/im/configs"
	"github.com/im/internal/media"
	"github.com/minio/minio-go/v7"
	"github.com/minio/minio-go/v7/pkg/credentials"
)

// MinioAPI 用 minio-go 实现 ObjectAPI。
type MinioAPI struct {
	client *minio.Client
	bucket string
}

// NewMinioAPI 创建 S3 兼容客户端。只做本地初始化，不发起网络请求。
func NewMinioAPI(cfg configs.MediaS3Config) (*MinioAPI, error) {
	endpoint := strings.TrimSpace(cfg.Endpoint)
	endpoint = strings.TrimPrefix(strings.TrimPrefix(endpoint, "https://"), "http://")
	endpoint = strings.TrimRight(endpoint, "/")
	if endpoint == "" || cfg.Bucket == "" || cfg.AccessKey == "" || cfg.SecretKey == "" {
		return nil, errors.New("s3 endpoint, bucket, access_key and secret_key are required")
	}
	lookup := minio.BucketLookupDNS
	if cfg.PathStyle {
		lookup = minio.BucketLookupPath
	}
	client, err := minio.New(endpoint, &minio.Options{
		Creds:        credentials.NewStaticV4(cfg.AccessKey, cfg.SecretKey, ""),
		Secure:       cfg.UseSSL,
		Region:       cfg.Region,
		BucketLookup: lookup,
	})
	if err != nil {
		return nil, err
	}
	return &MinioAPI{client: client, bucket: cfg.Bucket}, nil
}

// List 实现 ObjectAPI（递归列出前缀下所有对象）。
func (m *MinioAPI) List(ctx context.Context, prefix string) ([]ObjectInfo, error) {
	var out []ObjectInfo
	for obj := range m.client.ListObjects(ctx, m.bucket, minio.ListObjectsOptions{Prefix: prefix, Recursive: true}) {
		if obj.Err != nil {
			return nil, obj.Err
		}
		out = append(out, ObjectInfo{Key: obj.Key, Size: obj.Size})
	}
	return out, nil
}

// Exists 实现 ObjectAPI。
func (m *MinioAPI) Exists(ctx context.Context, key string) (bool, error) {
	_, err := m.client.StatObject(ctx, m.bucket, key, minio.StatObjectOptions{})
	if err == nil {
		return true, nil
	}
	if isNotFound(err) {
		return false, nil
	}
	return false, err
}

// Get 实现 ObjectAPI。
func (m *MinioAPI) Get(ctx context.Context, key string) (io.ReadCloser, error) {
	obj, err := m.client.GetObject(ctx, m.bucket, key, minio.GetObjectOptions{})
	if err != nil {
		return nil, err
	}
	if _, err := obj.Stat(); err != nil {
		obj.Close()
		if isNotFound(err) {
			return nil, media.ErrNotFound
		}
		return nil, err
	}
	return obj, nil
}

// Presign 实现 ObjectAPI。
func (m *MinioAPI) Presign(ctx context.Context, key string, ttl time.Duration) (string, error) {
	u, err := m.client.PresignedGetObject(ctx, m.bucket, key, ttl, url.Values{})
	if err != nil {
		return "", err
	}
	return u.String(), nil
}

func isNotFound(err error) bool {
	code := minio.ToErrorResponse(err).Code
	return code == "NoSuchKey" || code == "NotFound" || minio.ToErrorResponse(err).StatusCode == 404
}
