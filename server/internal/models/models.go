package models

import "time"

type MediaFile struct {
	Name         string    `json:"name"`
	Path         string    `json:"path"`
	RelativePath string    `json:"relative_path"`
	Size         int64     `json:"size"`
	ModifiedTime time.Time `json:"modified_time"`
	MediaType    string    `json:"media_type"`
	Extension    string    `json:"extension"`
}

type Folder struct {
	Name         string    `json:"name"`
	Path         string    `json:"path"`
	RelativePath string    `json:"relative_path"`
	IsRoot       bool      `json:"is_root"`
	ModifiedTime time.Time `json:"modified_time"`
}

type BrowseResult struct {
	CurrentPath string      `json:"current_path"`
	Folders     []Folder    `json:"folders"`
	Files       []MediaFile `json:"files"`
}

type PaginatedMediaFiles struct {
	Items    []MediaFile `json:"items"`
	Total    int         `json:"total"`
	Page     int         `json:"page"`
	PageSize int         `json:"page_size"`
	HasMore  bool        `json:"has_more"`
}

type SearchResult struct {
	Query   string      `json:"query"`
	Folders []Folder    `json:"folders"`
	Files   []MediaFile `json:"files"`
}

type FileTag struct {
	ID    string `json:"id"`
	Name  string `json:"name"`
	Color string `json:"color"`
}

type TagsData struct {
	Tags         []FileTag         `json:"tags"`
	Associations []FileAssociation `json:"associations"`
}

type FileAssociation struct {
	FilePath string `json:"file_path"`
	TagID    string `json:"tag_id"`
}

type ServerStatus struct {
	Running bool   `json:"running"`
	Host    string `json:"host"`
	Port    int    `json:"port"`
	IP      string `json:"ip"`
}
