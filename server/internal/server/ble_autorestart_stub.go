//go:build !(windows && bluetooth)

// Stub for builds that are NOT (windows && bluetooth). Provides a no-op
// wireBleAutoRestart so New compiles without dragging in BleHealthMonitor /
// NewSelfRestarter (which are windows && bluetooth guarded in internal/ble).
// On these builds BLE is unavailable at the scanner level anyway, so there is
// nothing to monitor.

package server

func (s *Server) wireBleAutoRestart() {}
