package proto

import (
	"encoding/json"
	"errors"
)

// ErrSignalType 表示信令内容不是带非空 type 字段的 JSON 对象。
var ErrSignalType = errors.New(`signal content must be {"type":"<ns>.<name>","data":...}`)

// SignalContent 是 CmdSignal 的标准内容格式：type 为 "<命名空间>.<名称>"（如 "paircast.playback"），
// data 由业务自定义。
type SignalContent struct {
	Type string          `json:"type"`
	Data json.RawMessage `json:"data,omitempty"`
}

// EncodeSignal 按标准格式编码信令内容。
func EncodeSignal(typ string, data any) (string, error) {
	if typ == "" {
		return "", ErrSignalType
	}
	raw, err := json.Marshal(data)
	if err != nil {
		return "", err
	}
	out, err := json.Marshal(SignalContent{Type: typ, Data: raw})
	return string(out), err
}

// ParseSignal 解析标准信令内容，type 缺失时返回 ErrSignalType。
func ParseSignal(content string) (SignalContent, error) {
	var s SignalContent
	if err := json.Unmarshal([]byte(content), &s); err != nil || s.Type == "" {
		return SignalContent{}, ErrSignalType
	}
	return s, nil
}
