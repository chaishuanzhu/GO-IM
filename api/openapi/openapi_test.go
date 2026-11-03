package openapi

import (
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"regexp"
	"strings"
	"testing"

	"github.com/im/internal/serverapi"
)

func get(t *testing.T, h http.Handler, path string) (*http.Response, string) {
	t.Helper()
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, path, nil))
	res := rec.Result()
	body, _ := io.ReadAll(res.Body)
	return res, string(body)
}

func TestHandlerServesSpecAndUI(t *testing.T) {
	h := Handler("/swagger/")

	res, body := get(t, h, "/swagger/openapi.yaml")
	if res.StatusCode != http.StatusOK || !strings.HasPrefix(body, "openapi: 3.") {
		t.Fatalf("spec: status %d, body starts %.20q", res.StatusCode, body)
	}
	if ct := res.Header.Get("Content-Type"); !strings.HasPrefix(ct, "application/yaml") {
		t.Errorf("spec content type = %q", ct)
	}

	res, body = get(t, h, "/swagger/")
	if res.StatusCode != http.StatusOK || !strings.Contains(body, "/swagger/openapi.yaml") {
		t.Fatalf("ui: status %d, missing spec URL", res.StatusCode)
	}
}

// 网关在 main.go 注册的每个 HTTP 路由都必须出现在文档里。
func TestSpecCoversGatewayRoutes(t *testing.T) {
	var src []byte
	for _, f := range []string{"../../cmd/gateway/main.go"} {
		b, err := os.ReadFile(f)
		if err != nil {
			t.Fatal(err)
		}
		src = append(src, b...)
	}
	spec := string(Spec)
	routes := regexp.MustCompile(`mux\.Handle(?:Func)?\("(/[^"]*)"`).FindAllStringSubmatch(string(src), -1)
	if len(routes) < 30 {
		t.Fatalf("found only %d routes in main.go", len(routes))
	}
	for _, m := range routes {
		route := m[1]
		if route == "/swagger/" {
			continue
		}
		key := "\n  " + route + ":"
		if strings.HasSuffix(route, "/") {
			key = "\n  " + route + "{"
		}
		if !strings.Contains(spec, key) {
			t.Errorf("route %s is not documented in openapi.yaml", route)
		}
	}
}

// 服务端 API 的路由与文档一一对应（main.go 按 Routes() 注册）。
func TestSpecMatchesServerAPIRoutes(t *testing.T) {
	spec := string(Spec)
	mounted := map[string]bool{}
	for _, route := range serverapi.New(serverapi.Deps{}).Routes() {
		mounted[route] = true
		if !strings.Contains(spec, "\n  "+route+":") {
			t.Errorf("server api route %s is not documented in openapi.yaml", route)
		}
	}
	for _, m := range regexp.MustCompile(`\n  (`+serverapi.Prefix+`[^:]+):`).FindAllStringSubmatch(spec, -1) {
		if !mounted[m[1]] {
			t.Errorf("openapi.yaml documents %s but the server api has no such route", m[1])
		}
	}
}
