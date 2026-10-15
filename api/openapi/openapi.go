// Package openapi 内嵌网关 HTTP 接口的 OpenAPI 文档，并提供 Swagger UI。
package openapi

import (
	_ "embed"
	"net/http"

	"github.com/swaggest/swgui/v5emb"
)

// Spec 是 openapi.yaml 的内容。
//
//go:embed openapi.yaml
var Spec []byte

// Handler 在 basePath（以 / 结尾）下提供 Swagger UI，文档本身位于 basePath + "openapi.yaml"。
// UI 静态资源内嵌在二进制中，不依赖外部 CDN。
func Handler(basePath string) http.Handler {
	specPath := basePath + "openapi.yaml"
	ui := v5emb.New("GO-IM API", specPath, basePath)
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == specPath {
			w.Header().Set("Content-Type", "application/yaml; charset=utf-8")
			w.Header().Set("Cache-Control", "no-cache")
			_, _ = w.Write(Spec)
			return
		}
		ui.ServeHTTP(w, r)
	})
}
