package systray

import (
	"fmt"
	"os/exec"
	"strings"

	"github.com/getlantern/systray"
)

type Tray struct {
	srvURL string
	onQuit func()
}

func New(srvURL string, onQuit func()) *Tray {
	return &Tray{
		srvURL: srvURL,
		onQuit: onQuit,
	}
}

// Run starts the system tray icon. This blocks until systray.Quit() is called.
func (t *Tray) Run() {
	systray.Run(t.onReady, t.onExit)
}

func (t *Tray) onReady() {
	systray.SetIcon(trayIconBytes)
	systray.SetTitle("LMH")
	systray.SetTooltip("LocalMediaHub - Running")

	mStatus := systray.AddMenuItem("Status: Running", "Server status")
	mStatus.Disable()

	systray.AddSeparator()

	mCopy := systray.AddMenuItem(fmt.Sprintf("Copy URL: %s", t.srvURL), "Copy server URL to clipboard")

	systray.AddSeparator()

	mQuit := systray.AddMenuItem("Quit", "Quit LocalMediaHub")

	go func() {
		for {
			select {
			case <-mCopy.ClickedCh:
				// 通过 stdin 喂给 clip.exe，避免把 URL 拼进 cmd /c 字符串（CWE-78）
				clipCmd := exec.Command("clip")
				clipCmd.Stdin = strings.NewReader(t.srvURL)
				clipCmd.Start()
			case <-mQuit.ClickedCh:
				if t.onQuit != nil {
					t.onQuit()
				}
				systray.Quit()
			}
		}
	}()
}

func (t *Tray) onExit() {}
