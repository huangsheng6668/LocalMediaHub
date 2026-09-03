//go:build !windows

// Stub for non-Windows builds. Provides a no-op wireBleAutoRestart so New
// compiles without dragging in BleHealthMonitor / NewSelfRestarter (which are
// windows guarded in internal/ble). On these builds BLE stuck-detection
// self-restart is not implemented; BLE scanning/connect still works.

package server

func (s *Server) wireBleAutoRestart() {}
