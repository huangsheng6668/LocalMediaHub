package systray

import (
	"fmt"
	"os/exec"

	"github.com/getlantern/systray"
)

type Tray struct {
	srvURL  string
	onQuit  func()
	running bool
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
				exec.Command("cmd", "/c", fmt.Sprintf("echo %s | clip", t.srvURL)).Start()
			case <-mQuit.ClickedCh:
				t.running = false
				if t.onQuit != nil {
					t.onQuit()
				}
				systray.Quit()
			}
		}
	}()
}

func (t *Tray) onExit() {}

func (t *Tray) SetStatus(running bool) {
	t.running = running
}
