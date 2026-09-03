package service

import (
	"context"
	"os/exec"
	"reflect"
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

func TestResolveVCodecParam(t *testing.T) {
	auto := resolvedEncoder{Name: "h264_qsv"}
	usableNvencOnly := func(name string) bool { return name == "h264_nvenc" }
	noneUsable := func(string) bool { return false }
	cases := []struct {
		param  string
		usable func(string) bool
		want   string
	}{
		{"copy", nil, "copy"},
		{"", nil, "h264_qsv"},
		{"auto", nil, "h264_qsv"},
		{"h264_nvenc", usableNvencOnly, "h264_nvenc"}, // usable → explicit
		{"h264_amf", usableNvencOnly, "h264_qsv"},     // not usable → auto (distinct from requested)
		{"libx264", noneUsable, "libx264"},            // fallback always allowed, no probe needed
		{"h264_nvenc -x", noneUsable, "h264_qsv"},     // injection attempt → auto
		{"bogus", noneUsable, "h264_qsv"},             // unknown → auto
	}
	for _, tc := range cases {
		usable := tc.usable
		if usable == nil {
			usable = noneUsable
		}
		got := resolveVCodecParam(tc.param, usable, auto)
		if got.Name != tc.want {
			t.Errorf("resolveVCodecParam(%q) = %q, want %q", tc.param, got.Name, tc.want)
		}
	}
}

func TestBuildTranscodeArgs(t *testing.T) {
	src := `D:\media\a.mp4`
	cases := []struct {
		name  string
		start float64
		enc   resolvedEncoder
		want  []string
	}{
		{
			name:  "copy with seek",
			start: 12.5,
			enc:   resolvedEncoder{Name: "copy"},
			want:  []string{"-ss", "12.500", "-i", src, "-vcodec", "copy", "-acodec", "aac", "-f", "mp4", "-movflags", "frag_keyframe+empty_moov", "pipe:1"},
		},
		{
			name:  "libx264 fallback byte-identical to legacy inline args",
			start: 0,
			enc:   resolvedEncoder{Name: "libx264"},
			want:  []string{"-i", src, "-vcodec", "libx264", "-preset", "ultrafast", "-acodec", "aac", "-f", "mp4", "-movflags", "frag_keyframe+empty_moov", "pipe:1"},
		},
		{
			name:  "nvenc auto appends validated quality literals",
			start: 0,
			enc:   resolvedEncoder{Name: "h264_nvenc"},
			want:  []string{"-i", src, "-vcodec", "h264_nvenc", "-preset", "p4", "-tune", "hq", "-rc", "vbr", "-cq", "23", "-acodec", "aac", "-f", "mp4", "-movflags", "frag_keyframe+empty_moov", "pipe:1"},
		},
		{
			name:  "qsv",
			start: 0,
			enc:   resolvedEncoder{Name: "h264_qsv"},
			want:  []string{"-i", src, "-vcodec", "h264_qsv", "-global_quality", "23", "-acodec", "aac", "-f", "mp4", "-movflags", "frag_keyframe+empty_moov", "pipe:1"},
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got := buildTranscodeArgs(src, tc.start, tc.enc)
			if !reflect.DeepEqual(got, tc.want) {
				t.Fatalf("buildTranscodeArgs() =\n%q\nwant\n%q", got, tc.want)
			}
		})
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
