package ble

import (
	"context"
	"testing"
	"time"

	"tinygo.org/x/bluetooth"
)

// recordingRecorder is a test double for connectRecorder. It captures every
// RecordConnect outcome so the test can assert connectLocked wired the recorder
// in with the right ok/fail polarity.
type recordingRecorder struct {
	records []bool
}

func (r *recordingRecorder) RecordConnect(ok bool) { r.records = append(r.records, ok) }

// TestNewCentralScannerDoesNotPanic lives in central_test.go and runs against
// the real tinygo path (adapter.Enable fails gracefully on hosts without a
// usable radio). It is intentionally NOT duplicated in this file;
// central_adapter_test.go is the adapter companion that exercises
// the recorder seam only.

// TestConnectLocked_RecordsOutcomeOnRecorder verifies that connectLocked
// reports its outcome to the injected recorder exactly once per call. The test
// constructs a tinyGoCentralScanner against the real bluetooth.DefaultAdapter
// (no injection seam exists for the adapter), sets a recording recorder, and
// drives connectLocked with a context deadline. On any CI/dev host without a
// peer advertising at the bogus MAC, adapter.Connect fails fast and
// connectLocked returns an error → the recorder must see ok=false once. On a
// host that somehow connects, the recorder still sees exactly one outcome.
//
// The bogus MAC "AA:BB:CC:DD:EE:FF" is a locally-administered address no real
// peripheral advertises; the 4s context bounds any slow-scan host so the test
// never hangs.
func TestConnectLocked_RecordsOutcomeOnRecorder(t *testing.T) {
	rec := &recordingRecorder{}
	s := &tinyGoCentralScanner{
		adapter:   bluetooth.DefaultAdapter,
		recorder:  rec,
	}

	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
	defer cancel()
	err := s.connectLocked(ctx, "AA:BB:CC:DD:EE:FF")
	// We do NOT assert the error polarity: a host with no BLE radio errors
	// immediately; a host with a radio but no peer also errors (after the
	// retry loop). Either way exactly one outcome must be recorded.
	_ = err

	if len(rec.records) != 1 {
		t.Fatalf("expected exactly one RecordConnect call, got %d (records=%v)", len(rec.records), rec.records)
	}
	if rec.records[0] != false {
		t.Fatalf("expected the recorded outcome to be a failure (ok=false), got ok=%v", rec.records[0])
	}
}

// TestConnectLocked_NilRecorderDoesNotPanic confirms the deferred recorder call
// is nil-safe — a scanner with no recorder injected (the production default on
// non-windows hosts) must not fault when connectLocked runs.
func TestConnectLocked_NilRecorderDoesNotPanic(t *testing.T) {
	s := &tinyGoCentralScanner{
		adapter:  bluetooth.DefaultAdapter,
		recorder: nil,
	}
	defer func() {
		if r := recover(); r != nil {
			t.Fatalf("connectLocked panicked with nil recorder: %v", r)
		}
	}()
	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
	defer cancel()
	_ = s.connectLocked(ctx, "AA:BB:CC:DD:EE:FF")
}
