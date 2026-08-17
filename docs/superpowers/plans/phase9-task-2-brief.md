### Task 2: ValidateDeletion 根比较大小写不敏感（M-4）

**Files:**
- Modify: `server/internal/service/path.go:179`
- Test: `server/internal/service/path_test.go`（`TestValidateDeletionRejectsRootItself:269` 旁）

**Interfaces:**
- Consumes: 既有 `ValidateDeletion(root string, roots []string)` 签名不变。

- [ ] **Step 1: 写失败测试**

```go
func TestValidateDeletionRejectsRootItselfCaseInsensitive(t *testing.T) {
	root := filepath.Join(os.TempDir(), "LMH-CaseRoot")
	os.MkdirAll(root, 0755)
	defer os.RemoveAll(root)
	// Windows 保留用户提交的大小写，词法清洗不归一盘符以外的段
	variants := []string{strings.ToUpper(root), strings.ToLower(root)}
	for _, v := range variants {
		if _, err := ValidateDeletion(v, []string{root}); err == nil {
			t.Fatalf("ValidateDeletion(%q) should reject the root itself", v)
		}
	}
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd server && go test ./internal/service/ -run TestValidateDeletionRejectsRootItselfCaseInsensitive -v`
Expected: FAIL（`strings.ToUpper(root)` 变体通过了删除校验）

- [ ] **Step 3: 最小实现**

`path.go:179` 附近，把 `if resolved == absRoot` 改为：

```go
if resolved == absRoot || strings.EqualFold(resolved, absRoot) {
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd server && go test ./internal/service/ -v`
Expected: PASS（含既有全部 path 测试）

- [ ] **Step 5: 提交**

```bash
git add server/internal/service/path.go server/internal/service/path_test.go
git commit -m "fix(security): case-insensitive delete-root guard on Windows (Phase 9)"
```

---

