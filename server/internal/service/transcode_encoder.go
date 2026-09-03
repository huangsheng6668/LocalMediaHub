package service

import (
	"context"
	"log/slog"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Spec 2026-09-03-transcode-modernization: the transcode path resolves its
// video encoder through a two-level probe instead of always using libx264
// ultrafast. Level 1 (static): the encoder must be listed by
// "ffmpeg -hide_banner -encoders". Level 2 (runtime): a 5-frame testsrc
// micro-encode through the encoder must exit 0. Level 2 exists because on
// Windows an encoder can be compiled into ffmpeg yet be unusable through a
// missing or broken driver — validated on the dev host (ffmpeg 8.1.1):
// qsv and amf are listed but fail the runtime probe while nvenc passes.

// resolvedEncoder names an encoder the transcode path may use. Name is always
// one of the allowlisted keys of knownEncoderArgs (or "copy"), NEVER a raw
// client-supplied string — the vcodec query param is only ever used as a
// lookup key (CWE-78 posture, same boundary as sanitizeMediaArg).
type resolvedEncoder struct {
	Name string
}

// knownEncoderArgs maps allowlisted encoder names to their ffmpeg quality
// literals. The libx264 entry is byte-identical to the pre-modernization
// args so the fallback path has zero regression surface. Hardware literals
// were validated on the dev host (ffmpeg 8.1.1, 2026-09-03): nvenc
// "-preset p4 -tune hq -rc vbr -cq 23" encodes testsrc successfully; qsv
// and amf literals follow upstream docs (hardware absent locally; the probe
// gates their use anyway).
var knownEncoderArgs = map[string][]string{
	"h264_nvenc": {"-preset", "p4", "-tune", "hq", "-rc", "vbr", "-cq", "23"},
	"h264_qsv":   {"-global_quality", "23"},
	"h264_amf":   {"-quality", "balanced"},
	"libx264":    {"-preset", "ultrafast"},
}

// copyEncoderName is the pass-through pseudo-encoder ("vcodec=copy"): stream
// copy re-muxes without re-encoding, so it has no quality args and is NOT a
// member of knownEncoderArgs.
const copyEncoderName = "copy"

// fallbackEncoderName always terminates the chain; never probed.
const fallbackEncoderName = "libx264"

// encoderProber lazily resolves which hardware encoders are usable and which
// one the "auto" path should pick. Probing happens once per process on the
// first transcoded request (headless startup pays no subprocess cost, and
// hosts without GPUs pay nothing at all).
type encoderProber struct {
	preference []string

	once     sync.Once
	resolved resolvedEncoder
	usable   map[string]bool
	probed   bool

	// lister returns "ffmpeg -hide_banner -encoders" output. Injected by
	// tests; nil = real subprocess.
	lister func(ctx context.Context) string
	// validator runs the runtime micro-encode for one encoder. Injected by
	// tests; nil = real subprocess.
	validator func(ctx context.Context, name string) bool
}

func newEncoderProber(preference []string) *encoderProber {
	return &encoderProber{
		preference: preference,
		usable:     make(map[string]bool),
	}
}

// parseEncodersOutput parses "ffmpeg -hide_banner -encoders" output into the
// set of advertised video encoder names. Lines look like:
//
//	" V....D libx264              libx264 H.264 / AVC / MPEG-4 AVC ..."
//	" V..... h264_qsv             H.264 / AVC ... (Intel Quick Sync ...)"
//
// The flags column is the first whitespace-delimited field; video encoders
// have a flags field starting with V. Pure function for testability.
func parseEncodersOutput(out string) map[string]bool {
	encoders := make(map[string]bool)
	for _, line := range strings.Split(out, "\n") {
		fields := strings.Fields(strings.TrimSpace(line))
		if len(fields) < 2 {
			continue
		}
		if strings.HasPrefix(fields[0], "V") {
			encoders[fields[1]] = true
		}
	}
	return encoders
}

// probeEncoderRuntime runs a 5-frame 320x240 testsrc micro-encode through the
// named encoder. Exit 0 is the only reliable usability signal on Windows.
// All argv entries are fixed literals — zero client input.
func probeEncoderRuntime(ctx context.Context, name string) bool {
	ctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, ffmpegBin, "-hide_banner",
		"-f", "lavfi", "-i", "testsrc=duration=0.5:size=320x240:rate=10",
		"-frames:v", "5", "-c:v", name, "-f", "null", "-")
	// Windows ffmpeg may ignore the CTRL_BREAK_EVENT Go sends by default;
	// force-kill on timeout (same pattern as serveTranscoded).
	cmd.Cancel = func() error {
		if cmd.Process != nil {
			return cmd.Process.Kill()
		}
		return os.ErrProcessDone
	}
	return cmd.Run() == nil
}

// listEncodersStatic runs the level-1 static probe. Empty string when
// ffmpeg cannot be executed (the caller treats that as nothing listed and
// every candidate falls back).
func listEncodersStatic(ctx context.Context) string {
	ctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	out, err := exec.CommandContext(ctx, ffmpegBin, "-hide_banner", "-encoders").Output()
	if err != nil {
		return ""
	}
	return string(out)
}

// resolve runs the two-level probe once (sync.Once) and returns the encoder
// the auto path should use: the first preference entry that passes both
// levels, or libx264 when none does. It also records the full usable set so
// explicit vcodec requests can be validated.
func (p *encoderProber) resolve() resolvedEncoder {
	p.once.Do(func() {
		p.probed = true
		lister := p.lister
		if lister == nil {
			lister = listEncodersStatic
		}
		validator := p.validator
		if validator == nil {
			validator = probeEncoderRuntime
		}

		// Drop unknown names from the preference list (config typo → WARN,
		// never an argv injection vector — knownEncoderArgs is the gate).
		chain := make([]string, 0, len(p.preference))
		for _, name := range p.preference {
			if _, ok := knownEncoderArgs[name]; !ok || name == fallbackEncoderName {
				slog.Warn("transcode: unknown encoder in transcode.encoder_preference, ignoring", "encoder", name)
				continue
			}
			chain = append(chain, name)
		}

		ctx := context.Background()
		// Skip the static probe entirely when the filtered chain is empty —
		// an empty/mis-typed preference must not fork ffmpeg at all.
		static := map[string]bool{}
		if len(chain) > 0 {
			static = parseEncodersOutput(lister(ctx))
		}
		auto := resolvedEncoder{Name: fallbackEncoderName}
		for _, name := range chain {
			if !static[name] {
				slog.Warn("transcode: encoder listed in preference but absent from ffmpeg -encoders, skipping", "encoder", name)
				continue
			}
			if !validator(ctx, name) {
				slog.Warn("transcode: encoder failed runtime probe (driver unusable?), skipping", "encoder", name)
				continue
			}
			p.usable[name] = true
			if auto.Name == fallbackEncoderName {
				auto = resolvedEncoder{Name: name}
			}
		}
		p.resolved = auto
		slog.Info("transcode: encoder chain resolved", "auto", auto.Name, "usable", strings.Join(sortedKeys(p.usable), ","))
	})
	return p.resolved
}

// isUsable reports whether the named hardware encoder passed probing.
// Resolve() first so the answer is always post-probe (once.Do is idempotent).
func (p *encoderProber) isUsable(name string) bool {
	p.resolve()
	return p.usable[name]
}

// transcodeProbeStatus is the admin-facing probe snapshot.
type transcodeProbeStatus struct {
	Auto       string   `json:"auto"`
	Usable     []string `json:"usable"`
	Preference []string `json:"preference"`
}

func (p *encoderProber) status() transcodeProbeStatus {
	if !p.probed {
		// Not probed yet: report the preference without forcing a probe.
		return transcodeProbeStatus{Auto: "", Usable: []string{}, Preference: append([]string(nil), p.preference...)}
	}
	return transcodeProbeStatus{
		Auto:       p.resolved.Name,
		Usable:     sortedKeys(p.usable),
		Preference: append([]string(nil), p.preference...),
	}
}

// resolveVCodecParam maps the client vcodec query value to a resolved
// encoder per the frozen wire contract (spec 3.2):
//
//	copy              → stream copy (no re-encode)
//	"" / "auto"       → the probe-resolved auto encoder
//	allowlisted name  → that encoder IF it passed probing, else auto (WARN)
//	anything else     → silently auto (matches the pre-modernization posture
//	                    of treating unknown values as the software default)
//
// The client value is ONLY ever a lookup key against knownEncoderArgs —
// raw values never reach argv (CWE-78).
func resolveVCodecParam(param string, usable func(string) bool, auto resolvedEncoder) resolvedEncoder {
	switch {
	case param == copyEncoderName:
		return resolvedEncoder{Name: copyEncoderName}
	case param == "" || param == "auto":
		return auto
	default:
		if _, ok := knownEncoderArgs[param]; ok {
			if param == fallbackEncoderName || usable(param) {
				return resolvedEncoder{Name: param}
			}
			slog.Warn("transcode: requested encoder not usable on this host, falling back to auto", "requested", param, "auto", auto.Name)
		}
		// Unknown values (including injection attempts) fall back to auto.
		return auto
	}
}

// buildTranscodeArgs assembles the ffmpeg argv. Pure function: every
// dynamic value is either a validated float, or a file path that already
// passed sanitizeMediaArg; encoder names arrive only via the allowlist.
// The libx264 branch produces byte-identical args to the pre-modernization
// inline construction (zero regression surface for the fallback path).
func buildTranscodeArgs(srcPath string, startSec float64, enc resolvedEncoder) []string {
	args := make([]string, 0, 24)
	if startSec != 0 {
		args = append(args, "-ss", strconv.FormatFloat(startSec, 'f', 3, 64))
	}
	args = append(args, "-i", srcPath)
	if enc.Name == copyEncoderName {
		args = append(args, "-vcodec", copyEncoderName)
	} else {
		args = append(args, "-vcodec", enc.Name)
		args = append(args, knownEncoderArgs[enc.Name]...)
	}
	args = append(args,
		"-acodec", "aac",
		"-f", "mp4",
		"-movflags", "frag_keyframe+empty_moov",
		"pipe:1",
	)
	return args
}

// sortedKeys returns map keys in deterministic order for logs / status.
func sortedKeys(m map[string]bool) []string {
	keys := make([]string, 0, len(m))
	for k := range m {
		keys = append(keys, k)
	}
	for i := 1; i < len(keys); i++ {
		for j := i; j > 0 && keys[j] < keys[j-1]; j-- {
			keys[j], keys[j-1] = keys[j-1], keys[j]
		}
	}
	return keys
}
