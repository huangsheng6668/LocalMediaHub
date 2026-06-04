//go:build windows && cgo

package main

func isSystraySupported() bool {
	return true
}
