### Task 3: Web THEME_LABELS 冻结常量（M-10）

**Files:**
- Modify: `server/internal/web/readerPrefs.js`（THEME_PRESETS 之后）
- Test: `server/internal/web/readerPrefs.test.mjs`

**Interfaces:**
- Produces: `readerPrefs.THEME_LABELS`（`Object.freeze` 的 key→中文标签映射，key 集合与 `THEME_PRESETS` 对齐）；`settings.js:59-60,81` 既有引用不变。

- [ ] **Step 1: 写失败测试**

```js
// readerPrefs.test.mjs 追加
test('THEME_LABELS is frozen and aligned with THEME_PRESETS', () => {
    assert.ok(readerPrefs.THEME_LABELS, 'THEME_LABELS must be exported');
    assert.ok(Object.isFrozen(readerPrefs.THEME_LABELS));
    const presetKeys = Object.keys(readerPrefs.THEME_PRESETS);
    const labelKeys = Object.keys(readerPrefs.THEME_LABELS);
    assert.deepEqual([...labelKeys].sort(), [...presetKeys].sort());
    for (const v of Object.values(readerPrefs.THEME_LABELS)) {
        assert.equal(typeof v, 'string');
        assert.ok(v.length > 0);
    }
});
```

- [ ] **Step 2: 运行确认失败**

Run: `cd server/internal/web && node --test readerPrefs.test.mjs`
Expected: FAIL（THEME_LABELS undefined）

- [ ] **Step 3: 实现**

`readerPrefs.js` 的 `THEME_PRESETS` 定义之后新增：

```js
// 主题展示名（settings 页网格）。settings.js 的 // XSS-SAFE: 豁免以"本常量已冻结
// 且值全部为字面量"为前提 —— 勿在此对象中放入任何动态/用户数据。
export const THEME_LABELS = Object.freeze({
    DAY: '日间',
    DAY_BRIGHT: '纯白',
    EYE_CARE: '护眼米色',
    EYE_CARE_GREEN: '护眼绿',
    PARCHMENT: '羊皮纸',
    NIGHT: '深灰夜间',
    NIGHT_BLACK: '纯黑夜间',
    AUTO: '跟随系统',
});
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd server/internal/web && node --test`
Expected: 全部 PASS

- [ ] **Step 5: 提交**

```bash
git add server/internal/web/readerPrefs.js server/internal/web/readerPrefs.test.mjs
git commit -m "fix(web): export frozen THEME_LABELS for settings theme grid (Phase 9)"
```

---

