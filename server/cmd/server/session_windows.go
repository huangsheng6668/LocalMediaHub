//go:build windows

package main

import (
	"strings"
	"syscall"
	"unsafe"
)

var (
	user32                    = syscall.NewLazyDLL("user32.dll")
	getProcessWindowStation   = user32.NewProc("GetProcessWindowStation")
	getUserObjectInformationW = user32.NewProc("GetUserObjectInformationW")
)

const UOI_NAME = 2

func isInteractiveSession() bool {
	hWinSta, _, _ := getProcessWindowStation.Call()
	if hWinSta == 0 {
		return false
	}
	var needed uint32
	// Query length first
	getUserObjectInformationW.Call(hWinSta, UOI_NAME, 0, 0, uintptr(unsafe.Pointer(&needed)))
	if needed == 0 {
		return false
	}
	buf := make([]uint16, needed/2+1)
	var length uint32
	ret, _, _ := getUserObjectInformationW.Call(hWinSta, UOI_NAME, uintptr(unsafe.Pointer(&buf[0])), uintptr(needed), uintptr(unsafe.Pointer(&length)))
	if ret == 0 {
		return false
	}
	name := syscall.UTF16ToString(buf)
	return strings.ToLower(name) == "winsta0"
}
