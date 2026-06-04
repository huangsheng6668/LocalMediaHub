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
	findWindowW               = user32.NewProc("FindWindowW")
	createWindowExW           = user32.NewProc("CreateWindowExW")
	destroyWindow             = user32.NewProc("DestroyWindow")
)

const UOI_NAME = 2

func canCreateWindow() bool {
	className, _ := syscall.UTF16PtrFromString("STATIC")
	windowName, _ := syscall.UTF16PtrFromString("TestWindow")
	
	// HWND_MESSAGE is ((HWND)-3)
	hwndParent := uintptr(0xfffffffffffffffd) // -3 as uintptr
	
	hTest, _, _ := createWindowExW.Call(
		0,                                    // dwExStyle
		uintptr(unsafe.Pointer(className)),   // lpClassName
		uintptr(unsafe.Pointer(windowName)),  // lpWindowName
		0,                                    // dwStyle
		0, 0, 0, 0,                           // x, y, nWidth, nHeight
		hwndParent,                           // hWndParent
		0,                                    // hMenu
		0,                                    // hInstance
		0,                                    // lpParam
	)
	
	if hTest == 0 {
		return false
	}
	
	destroyWindow.Call(hTest)
	return true
}

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
	if strings.ToLower(name) != "winsta0" {
		return false
	}

	// Check if taskbar/system tray is available
	className, _ := syscall.UTF16PtrFromString("Shell_TrayWnd")
	hTray, _, _ := findWindowW.Call(uintptr(unsafe.Pointer(className)), 0)
	if hTray == 0 {
		return false
	}

	// Check if we can successfully create a message-only window
	return canCreateWindow()
}
