package handler

import (
	"net/http"

	"github.com/labstack/echo/v4"
)

type RootResponse struct {
	Name    string `json:"name"`
	Version string `json:"version"`
}

func (h *Handler) Root(c echo.Context) error {
	return c.JSON(http.StatusOK, RootResponse{
		Name:    "LocalMediaHub",
		Version: "0.2.0",
	})
}
