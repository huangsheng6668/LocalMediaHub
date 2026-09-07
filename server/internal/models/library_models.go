package models

import "encoding/json"

// ProgressUpdate 是 POST /api/v1/library/states 的请求体。
type ProgressUpdate struct {
	Path         string  `json:"path"`
	ChapterIndex int     `json:"chapter_index"`
	ParaIndex    int     `json:"para_index"`
	Percent      float64 `json:"percent"`
	Finished     bool    `json:"finished"`
	LastReadAt   int64   `json:"last_read_at"` // Unix 毫秒
	// 本次新增阅读秒数（服务端累加；0 = 纯进度上报。负数忽略，>3600 clamp 到 3600）。
	ReadSecondsDelta int64 `json:"read_seconds_delta"`
}

// ReadingState 是单本书的完整状态行（GET states 响应 / 内部行模型）。
type ReadingState struct {
	Path         string  `json:"path,omitempty"`
	ChapterIndex int     `json:"chapter_index"`
	ParaIndex    int     `json:"para_index"`
	Percent      float64 `json:"percent"`
	Finished     bool    `json:"finished"`
	ManualStatus *string `json:"manual_status"`
	LastReadAt   int64   `json:"last_read_at"`
	UpdatedAt    int64   `json:"updated_at"`
	ReadSeconds  int64   `json:"read_seconds"`
}

// ReadingStateBadge 是 decorations 批量响应里的单条徽章。
type ReadingStateBadge struct {
	Status      string  `json:"status"`
	Percent     float64 `json:"percent"`
	LastReadAt  int64   `json:"last_read_at"`
	ReadSeconds int64   `json:"read_seconds"`
}

// DecorationsResult 是 POST /api/v1/library/decorations 的响应。
type DecorationsResult struct {
	States    map[string]ReadingStateBadge `json:"states"`
	Favorites []string                     `json:"favorites"`
}

// StatsSummary 是 GET /api/v1/library/stats/summary 的响应（阅读时长聚合）。
type StatsSummary struct {
	TodaySeconds int64 `json:"today_seconds"`
	WeekSeconds  int64 `json:"week_seconds"` // 近 7 天含今日
	TotalSeconds int64 `json:"total_seconds"`
}

// FavoriteRecord 是服务端收藏行（snapshot 为客户端 JSON，服务端不解释）。
type FavoriteRecord struct {
	Path      string          `json:"path"`
	IsDir     bool            `json:"is_dir"`
	IsSystem  bool            `json:"is_system"`
	Title     string          `json:"title"`
	MediaType string          `json:"media_type"`
	Snapshot  json.RawMessage `json:"snapshot"`
	AddedAt   int64           `json:"added_at"`
}

// FavoriteUpdate 是 POST /api/v1/library/favorites 的请求体。
type FavoriteUpdate struct {
	Path      string          `json:"path"`
	IsDir     bool            `json:"is_dir"`
	IsSystem  bool            `json:"is_system"`
	Title     string          `json:"title"`
	MediaType string          `json:"media_type"`
	Snapshot  json.RawMessage `json:"snapshot"`
	AddedAt   int64           `json:"added_at"`
}
