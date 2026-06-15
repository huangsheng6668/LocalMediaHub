package handler

// This file previously held Root / RootResponse handlers that were never
// registered on any route. They have been removed as dead code. The root API
// information is instead surfaced via the embedded web UI and the /health
// endpoint registered in server.go.
