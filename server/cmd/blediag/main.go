// Temporary BLE diagnostic: dump ALL nearby advertisements (address, name,
// RSSI, service UUIDs) with no UUID filter, so we can see whether the phone's
// advertisement is visible to Windows and what UUID byte-order WinRT reports.
// Not part of the product; delete after use.
package main

import (
	"fmt"
	"time"

	"tinygo.org/x/bluetooth"
)

func main() {
	adapter := bluetooth.DefaultAdapter
	if err := adapter.Enable(); err != nil {
		fmt.Println("adapter enable failed:", err)
		return
	}
	fmt.Println("scanning all BLE advertisements for 12s ...")
	stop := time.After(12 * time.Second)
	done := make(chan struct{})
	go func() {
		err := adapter.Scan(func(_ *bluetooth.Adapter, d bluetooth.ScanResult) {
			uuids := d.ServiceUUIDs()
			parts := make([]string, len(uuids))
			for i, u := range uuids {
				parts[i] = u.String()
			}
			fmt.Printf("DEV addr=%s name=%q rssi=%d uuids=%v\n",
				d.Address.String(), d.LocalName(), d.RSSI, parts)
		})
		fmt.Println("scan ended:", err)
		close(done)
	}()
	select {
	case <-stop:
		_ = adapter.StopScan()
		<-done
	case <-done:
	}
	fmt.Println("diag done")
}
