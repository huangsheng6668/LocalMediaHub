import { test } from 'node:test';
import assert from 'node:assert/strict';
import { migrateV1toV2 } from './readerPrefs.js';

const VALID = ['NONE', 'COVER', 'SIMULATION', 'DRAG'];

test('default pageTurnStyle is NONE', () => {
    assert.equal(migrateV1toV2(null).pageTurnStyle, 'NONE');
});

test('migrate keeps valid pageTurnStyle values', () => {
    for (const v of VALID) {
        assert.equal(migrateV1toV2({ pageTurnStyle: v }).pageTurnStyle, v);
    }
});

test('migrate drops invalid pageTurnStyle to NONE', () => {
    assert.equal(migrateV1toV2({ pageTurnStyle: 'BOGUS' }).pageTurnStyle, 'NONE');
    assert.equal(migrateV1toV2({ pageTurnStyle: 123 }).pageTurnStyle, 'NONE');
});

test('migrate preserves pageTurnStyle when other fields present', () => {
    const s = migrateV1toV2({ pageTurnStyle: 'DRAG', theme: 'NIGHT' });
    assert.equal(s.pageTurnStyle, 'DRAG');
    assert.equal(s.theme, 'NIGHT');
});
