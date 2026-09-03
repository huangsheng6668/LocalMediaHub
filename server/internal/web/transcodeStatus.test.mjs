import test from 'node:test';
import assert from 'node:assert/strict';
import { formatTranscodeStatus } from './utils.js';

test('formatTranscodeStatus: null payload maps to degraded placeholders', () => {
    const r = formatTranscodeStatus(null);
    assert.equal(r.encoder, '状态不可用');
    assert.equal(r.sessions, '—');
});

test('formatTranscodeStatus: unprobed chain shows lazy-probe hint', () => {
    const r = formatTranscodeStatus({ active: 0, max_sessions: 3, probe: { auto: '', usable: [], preference: ['h264_nvenc'] } });
    assert.match(r.encoder, /未探测/);
    assert.equal(r.sessions, '0 / 3');
});

test('formatTranscodeStatus: known hardware encoders get friendly labels', () => {
    for (const [name, label] of Object.entries({
        h264_nvenc: 'NVIDIA NVENC 硬编',
        h264_qsv: 'Intel QSV 硬编',
        h264_amf: 'AMD AMF 硬编',
        libx264: '软件编码 (libx264)',
    })) {
        const r = formatTranscodeStatus({ active: 1, max_sessions: 3, probe: { auto: name, usable: [name] } });
        assert.equal(r.encoder, label, name);
        assert.equal(r.sessions, '1 / 3');
    }
});

test('formatTranscodeStatus: unknown encoder name falls back to the raw name', () => {
    const r = formatTranscodeStatus({ active: 0, max_sessions: 3, probe: { auto: 'h264_videotoolbox', usable: [] } });
    assert.equal(r.encoder, 'h264_videotoolbox');
});

test('formatTranscodeStatus: negative max_sessions renders as unlimited', () => {
    const r = formatTranscodeStatus({ active: 2, max_sessions: -1, probe: { auto: 'h264_nvenc', usable: ['h264_nvenc'] } });
    assert.equal(r.sessions, '2 / 不限');
});

test('formatTranscodeStatus: missing fields tolerate gracefully', () => {
    const r = formatTranscodeStatus({});
    assert.match(r.encoder, /未探测/);
    assert.equal(r.sessions, '0 / 不限');
});
