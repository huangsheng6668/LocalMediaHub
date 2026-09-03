package service

import (
	"context"
	"os/exec"
	"testing"
)

const cannedEncodersOutput = ` ffmpeg version 8.1.1-full
Encoders:
 V....D libx264              libx264 H.264 / AVC / MPEG-4 AVC / MPEG-4 part 10 (codec h264)
 V....D libx264rgb           libx264 H.264 ...
 V....D h264_amf             AMD AMF H.264 Encoder (codec h264)
 V....D h264_nvenc           NVIDIA NVENC H.264 encoder (codec h264)
 V..... h264_qsv             H.264 / AVC ... (Intel Quick Sync Video ...)
 A....D aac                  AAC (Advanced Audio Coding)
 `

func TestParseEncodersOutput(t *testing.T) {
	got := parseEncodersOutput(cannedEncodersOutput)
	wantPresent := []string{"libx264", "libx264rgb", "h264_amf", "h264_nvenc", "h264_qsv"}
	for _, name := range wantPresent {
		if !got[name] {
			t.Errorf("expected %q parsed as present", name)
		}
	}
	if got["aac"] {
		t.Errorf("audio encoder must not be parsed as a video encoder")
	}
	if got["ffmpeg"] || got["Encoders:"] {
		t.Errorf("header lines must not be parsed as encoders: %v", got)
	}
}

func TestEncoderProberResolveFirstUsable(t *testing.T) {
	p := newEncoderProber([]string{"h264_nvenc", "h264_qsv", "h264_amf"})
	p.lister = func(context.Context) string { return cannedEncodersOutput }
	p.validator = func(_ context.Context, name string) bool {
		return name == "h264_qsv" // nvenc runtime-broken, qsv passes
	}
	got := p.resolve()
	if got.Name != "h264_qsv" {
		t.Fatalf("resolve() = %q, want h264_qsv (first usable in preference order)", got.Name)
	}
	if !p.isUsable("h264_qsv") {
		t.Fatal("isUsable(h264_qsv) = false, want true")
	}
	if p.isUsable("h264_nvenc") {
		t.Fatal("isUsable(h264_nvenc) = true, want false (failed runtime probe)")
	}
}

func TestEncoderProberAllFailFallsBackToLibx264(t *testing.T) {
	p := newEncoderProber([]string{"h264_nvenc", "h264_qsv"})
	p.lister = func(context.Context) string { return cannedEncodersOutput }
	p.validator = func(context.Context, string) bool { return false }
	if got := p.resolve(); got.Name != fallbackEncoderName {
		t.Fatalf("resolve() = %q, want libx264 fallback", got.Name)
	}
}

func TestEncoderProberSkipsUnlistedEncoders(t *testing.T) {
	p := newEncoderProber([]string{"h264_nvenc", "h264_amf"})
	// Static output lists ONLY nvenc.
	p.lister = func(context.Context) string { return " V....D h264_nvenc  NVIDIA NVENC H.264 encoder (codec h264)" }
	p.validator = func(_ context.Context, name string) bool {
		if name == "h264_amf" {
			t.Error("runtime probe must not run for an encoder absent from the static list")
		}
		return true
	}
	if got := p.resolve(); got.Name != "h264_nvenc" {
		t.Fatalf("resolve() = %q, want h264_nvenc", got.Name)
	}
}

func TestEncoderProberDropsUnknownPreferenceEntries(t *testing.T) {
	p := newEncoderProber([]string{"h264_nvenc; rm -rf /", "h264_nvenc", "libx264"})
	p.lister = func(context.Context) string { return cannedEncodersOutput }
	p.validator = func(_ context.Context, name string) bool { return name == "h264_nvenc" }
	if got := p.resolve(); got.Name != "h264_nvenc" {
		t.Fatalf("resolve() = %q, want h264_nvenc (unknown + fallback entries dropped)", got.Name)
	}
}

func TestEncoderProberEmptyPreferenceUsesFallbackDirectly(t *testing.T) {
	p := newEncoderProber(nil)
	called := false
	p.lister = func(context.Context) string { called = true; return cannedEncodersOutput }
	if got := p.resolve(); got.Name != fallbackEncoderName {
		t.Fatalf("resolve() = %q, want libx264", got.Name)
	}
	if called {
		t.Fatal("empty preference must not spawn the lister at all")
	}
}

func TestEncoderProberStatusBeforeAndAfterResolve(t *testing.T) {
	p := newEncoderProber([]string{"h264_nvenc", "h264_qsv", "h264_amf"})
	st := p.status()
	if st.Auto != "" || len(st.Usable) != 0 || len(st.Preference) != 3 {
		t.Fatalf("pre-resolve status = %+v, want empty auto/usable + preference of 3", st)
	}
	p.lister = func(context.Context) string { return cannedEncodersOutput }
	p.validator = func(_ context.Context, name string) bool { return name == "h264_nvenc" }
	p.resolve()
	st = p.status()
	if st.Auto != "h264_nvenc" || len(st.Usable) != 1 || st.Usable[0] != "h264_nvenc" {
		t.Fatalf("post-resolve status = %+v, want auto=h264_nvenc usable=[h264_nvenc]", st)
	}
}

// TestEncoderProberRealFFmpeg runs the REAL two-level probe against the
// host ffmpeg. Skipped under -short and when ffmpeg is not on PATH. It
// asserts only that the outcome is a valid allowlisted encoder — the dev
// host itself demonstrates the interesting case (nvenc usable, qsv/amf
// listed but runtime-broken).
func TestEncoderProberRealFFmpeg(t *testing.T) {
	if testing.Short() {
		t.Skip("skipping real-ffmpeg probe in -short mode")
	}
	if _, err := exec.LookPath(ffmpegBin); err != nil {
		t.Skipf("ffmpeg not on PATH: %v", err)
	}
	p := newEncoderProber([]string{"h264_nvenc", "h264_qsv", "h264_amf"})
	got := p.resolve()
	if _, ok := knownEncoderArgs[got.Name]; !ok {
		t.Fatalf("real probe resolved %q which is not allowlisted", got.Name)
	}
	t.Logf("real probe resolved auto=%s usable=%v", got.Name, p.status().Usable)
}
