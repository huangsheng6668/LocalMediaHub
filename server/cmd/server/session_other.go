//go:build !windows

package main

func isInteractiveSession() bool {
	return true
}
