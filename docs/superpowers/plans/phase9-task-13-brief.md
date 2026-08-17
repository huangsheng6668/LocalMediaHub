### Task 13: Server 杂项（L-2 / L-4 / L-5）

**Files:**
- Modify: `server/internal/server/server.go:251-254`（pprof 组）+ `server_test.go`
- Modify: `server/internal/service/tags.go:401-410` + 对应测试文件
- Modify: `server/internal/ble/ble_health.go` + `ble_health_test.go`

**Interfaces:**
- Produces:
  - `/debug/pprof` 组叠加 `authMw`（token 模式下持 token 才能访问；开放模式维持 PrivateNetOnly 语义）
  - `escapeLikePattern(s string) string`（`\`→`\\`、`%`→`\%`、`_`→`\_`），`CleanDeletedPath` 的所有 LIKE 参数改为 `escapeLikePattern(normPath) + sep + "%" ` 并在 SQL 追加 ` ESCAPE '\'`
  - BLE 自重启冷却指数退避：`BleHealthMonitor` 记录连续重启次数 `n`，冷却 = `min(1min << n, 2h)`；阈值/基础冷却保持现值

- [ ] **Step 1: 写失败测试**

```go
// tags 测试：路径含 % 与 _ 时只命中该前缀
func TestCleanDeletedPathEscapesLikeWildcards(t *testing.T) {
	// 建 tags 库：文件 "D:\Media\100%_great\a.mp4" 与 "D:\Media\100Xgreat\b.mp4"
	// CleanDeletedPath("D:\\Media\\100%_great") 后：前者关联被清，后者保留。
	（断言两条 SELECT 计数）
}

// ble_health 测试
func TestRestartCooldownBacksOffExponentially(t *testing.T) {
	h := newHealthMonitorForTest()
	if got := h.cooldownFor(0); got != time.Minute { t.Fatalf("n=0: %v", got) }
	if got := h.cooldownFor(3); got != 8*time.Minute { t.Fatalf("n=3: %v", got) }
	if got := h.cooldownFor(10); got != 2*time.Hour { t.Fatalf("n=10 cap: %v", got) }
}
```

（`cooldownFor(n int) time.Duration` 抽为纯函数：`d := base << min(n, k)` 封顶 2h，防 int 溢出用 `for n > 0 && d < cap { d *= 2; n-- }`。）

- [ ] **Step 2: 运行确认失败**

Run: `cd server && go test ./internal/service/ ./internal/ble/ -run 'TestCleanDeletedPathEscapes|TestRestartCooldown' -v`
Expected: FAIL

- [ ] **Step 3: 实现**

pprof：`s.Echo.Group("/debug/pprof", middleware.PrivateNetOnly(), authMw)`（确认 `authMw` 在作用域内，必要时上移声明）。tags：`escapeLikePattern` + SQL `LIKE ? ESCAPE '\'`（注意 Go 字符串里写 `"\\"`）。ble_health：`cooldownFor` + 连续计数（成功稳定运行 >10min 清零）。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd server && go test ./...`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add server/internal/server/server.go server/internal/server/server_test.go server/internal/service/tags.go server/internal/service/tags_test.go server/internal/ble/ble_health.go server/internal/ble/ble_health_test.go
git commit -m "fix(security): pprof token gate, LIKE escaping and BLE restart backoff (Phase 9)"
```

---

