# Security Round 29 — Phase 4: HTTP Hardening Design

> **日期**：2026-07-11
> **范围**：Go 服务端 HTTP 响应头加固（不含 TLS）
> **威胁模型**：局域网半可信（继承 Round 29 主 spec）
> **依赖**：Phase 1（Bearer Token 鉴权层，已合并于 commit `76b6d51`）
> **审计轮次**：Round 29 Phase 4
> **源 spec**：`docs/superpowers/specs/2026-07-10-security-audit-design.md`（section 5.4）

---

## 0. 摘要

Phase 4 落实主 spec 第 5.4 节的"HTTP 加固"修复（TLS 部分按主 spec 6.2 节决策留作未来）。针对 4 项发现：

- **T4-03（CVSS 4.3 Medium）**：缺 `X-Frame-Options` / `frame-ancestors` CSP → Clickjacking 风险
- **T4-04（CVSS 3.1 Low）**：缺 `nosniff` / CSP / `Referrer-Policy`
- **T1-05（CVSS 6.3 Medium，部分缓解）**：转码流 MITM 注入毒媒体——CSP 不直接缓解（CSP 是浏览器侧），但配合 `X-Content-Type-Options: nosniff` 收紧 MIME 解析
- **T8-06（Info）**：未启用 Trusted Types——条件性，留作 Phase 5 整改后

**核心改动**：
1. 新增 `middleware/SecurityHeaders()` 中间件，设置 4 个响应头
2. 挂载到 `registerRoutes` 全局（CORS 之前），所有响应（含静态资源 + OPTIONS 预检）均生效
3. README 记录新增头 + CSP `'unsafe-inline'` 的 TODO（指向 Phase 5）

**Why now**：Phase 1/3/7 已堵住鉴权 + 配置 + 签名三类风险；Phase 4 转向浏览器侧防御。CSP 即便 Phase 5 XSS 整改完成前也能提供"纵深防御"——若 escapeHtml 未来某处遗漏，CSP 的 `script-src 'self'` 会让 inline script 失效，阻断数据 exfiltration。

---

## 1. 范围与方法论

### 1.1 范围

仅服务端响应头中间件。**不含**：
- TLS（按主 spec 6.2 节决策留作未来）
- `Permissions-Policy`（YAGNI，项目不用相机/麦克风/地理位置等 API）
- `Strict-Transport-Security`（HSTS 仅 HTTPS 下生效，本轮无 TLS）
- Trusted Types（条件性，需 Web UI 完全 escape 化后启用）

### 1.2 方法论

继承 Round 29 主 spec 的"威胁建模 → 代码验证 → 修复"流程。

### 1.3 探索阶段已确认的事实

- `server/internal/web/index.html` 经 grep 验证：**1 个外部 `<script type="module" src="app.js">`**，**0 个 `<style>` 标签**，**7 处 inline `style="..."` 属性**。所以 `script-src 'self'` 可严格，`style-src` 需 `'unsafe-inline'`。
- Web UI **无 `data:` URI 使用**（grep `data:image|data:text|srcset.*data:` 无匹配），所以 `img-src` 不留 `data:` 例外。
- `server.go:103-108` 中间件挂载顺序：`Recover` → `Logger` → `CORS`。SecurityHeaders 将插在 CORS 之前。

---

## 2. 威胁与缓解对照

| 发现 ID | CVSS | 描述 | Phase 4 缓解 |
|---|---|---|---|
| **T4-03** | 4.3 Medium | 缺 `X-Frame-Options` | `X-Frame-Options: DENY` |
| **T4-04** | 3.1 Low | 缺 `nosniff` / CSP / `Referrer-Policy` | 三个头一次性补齐 |
| **T1-05** | 6.3 Medium | 转码流 MITM（浏览器侧不直接缓解） | `nosniff` 收紧 MIME 解析（间接） |
| **T8-06** | Info | 未启用 Trusted Types | 留作 Phase 5 后 |

**攻击链影响**：
- **Chain-F**（CVSS 5.4 Medium）：T4-01 XSS → 当前无 token 无用；若未来加 token，CSP 让 inline script 失效，阻断 XSS exfiltration 链。**条件性缓解**。

---

## 3. 设计决策

### 3.1 已确认决策（来自 brainstorming）

| 决策 | 选择 | 理由 |
|---|---|---|
| CSP 严格度 | **渐进式**：`script-src 'self'`；`style-src 'self' 'unsafe-inline'`（TODO 指向 Phase 5） | script 已外部化可严格；style 有 7 处 inline，立即禁止会破坏渲染 |
| TLS | **完全跳过本轮** | 主 spec 6.2 节明确"留作未来"；本轮不是触发时机 |
| `Permissions-Policy` | **不加** | YAGNI；项目不用相机/麦克风等 API |
| `img-src` | **`'self'`（不含 `data:`）** | 探索确认 Web UI 无 `data:` URI |
| 中间件挂载位置 | **CORS 之前** | OPTIONS 预检也带安全头 |
| Trusted Types | **留作 Phase 5 后** | 需 Web UI 完全 escape 化后启用 |

### 3.2 兼容性

| 现状 | Phase 4 后行为 | 破坏性 |
|---|---|---|
| Web UI 通过 `http.FileServer` 服务的静态资源 | 响应头带 4 个安全头 | 无（CSP 允许 'self'） |
| API JSON 响应 | 响应头带 4 个安全头 | 无（API 不渲染 HTML） |
| 视频/音频流（`media-src 'self'`） | 同源加载，CSP 允许 | 无 |
| 缩略图（`img-src 'self'`） | 同源加载，CSP 允许 | 无 |
| inline `style="..."`（index.html 7 处） | `style-src 'unsafe-inline'` 允许 | 无 |
| inline `<script>...</script>`（如有未来引入） | `script-src 'self'` 阻断 | 有（但当前无 inline script，未来引入需外部化） |

---

## 4. 修改清单

| 文件 | 改动 | Task |
|---|---|---|
| `server/internal/server/middleware/security_headers.go` | 新建 `SecurityHeaders()` 中间件 | 1 |
| `server/internal/server/middleware/security_headers_test.go` | 新建单元测试 | 1 |
| `server/internal/server/server.go` | `registerRoutes` 挂载中间件（CORS 之前） | 2 |
| `README.md` | 加"安全响应头"小节 | 3 |

---

## 5. 实施细节

### 5.1 `middleware/security_headers.go`

```go
package middleware

import (
	"github.com/labstack/echo/v4"
)

// SecurityHeaders adds browser security headers to all responses.
//
// Coverage:
//   - X-Frame-Options: DENY           → prevents clickjacking (T4-03)
//   - X-Content-Type-Options: nosniff → stops MIME sniffing (T4-04)
//   - Referrer-Policy: no-referrer   → prevents leaking URLs to external resources (T4-04)
//   - Content-Security-Policy        → restricts resource loading to self (T4-04);
//     provides defense-in-depth against XSS even if escapeHtml is missed somewhere
//
// TODO(Phase 5): once Web UI inline styles are migrated to external CSS,
// remove 'unsafe-inline' from style-src for full CSP strictness.
//
// Not added (intentional):
//   - Strict-Transport-Security: only effective under HTTPS; TLS is deferred
//     per main spec section 6.2.
//   - Permissions-Policy: project doesn't use camera/mic/geolocation APIs (YAGNI).
func SecurityHeaders() echo.MiddlewareFunc {
	return func(next echo.HandlerFunc) echo.HandlerFunc {
		return func(c echo.Context) error {
			h := c.Response().Header()
			h.Set("X-Frame-Options", "DENY")
			h.Set("X-Content-Type-Options", "nosniff")
			h.Set("Referrer-Policy", "no-referrer")
			h.Set("Content-Security-Policy",
				"default-src 'self'; "+
					"script-src 'self'; "+
					"style-src 'self' 'unsafe-inline'; "+
					"img-src 'self'; "+
					"media-src 'self'; "+
					"connect-src 'self'")
			return next(c)
		}
	}
}
```

### 5.2 `registerRoutes` 挂载

`server.go:100-108` 当前顺序：

```go
s.Echo.Use(echoMw.Recover())
s.Echo.Use(echoMw.Logger())
s.Echo.Use(middleware.CORS(allowedCORSOrigins(s.Config.Server.Port)))
```

改为：

```go
s.Echo.Use(echoMw.Recover())
s.Echo.Use(echoMw.Logger())
s.Echo.Use(middleware.SecurityHeaders())   // Phase 4: before CORS so headers apply to preflight OPTIONS too
s.Echo.Use(middleware.CORS(allowedCORSOrigins(s.Config.Server.Port)))
```

### 5.3 README "安全响应头" 小节

紧跟现有"### 3.1 Release 签名"或合适位置后插入：

```markdown
### 安全响应头

服务端对所有响应（含静态资源 + API + OPTIONS 预检）附加以下安全头：

| 头 | 值 | 缓解 |
|---|---|---|
| `X-Frame-Options` | `DENY` | Clickjacking（禁止任何站点 iframe 嵌入本服务） |
| `X-Content-Type-Options` | `nosniff` | MIME 嗅探攻击 |
| `Referrer-Policy` | `no-referrer` | 外链泄漏本服务 URL |
| `Content-Security-Policy` | `default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self'; media-src 'self'; connect-src 'self'` | XSS 数据 exfiltration（纵深防御） |

**CSP 说明**：
- `script-src 'self'`：JavaScript 全部同源（已外部化，不允许 inline script）。
- `style-src 'self' 'unsafe-inline'`：当前 Web UI 仍有 inline `style="..."` 属性，暂留 `'unsafe-inline'`。**待未来 Phase 5 Web UI XSS 整改完成后移除**。
- 不含 `data:` URI 例外（探索阶段确认 Web UI 无使用）。

**未加的头**：
- `Strict-Transport-Security`（HSTS）：仅 HTTPS 下有效，TLS 留作未来。
- `Permissions-Policy`：项目不使用相机/麦克风/地理位置等敏感 API。
```

---

## 6. 测试方案

### 6.1 单元测试

`security_headers_test.go` 表驱动：

```go
func TestSecurityHeaders(t *testing.T) {
	expectedHeaders := map[string]string{
		"X-Frame-Options":         "DENY",
		"X-Content-Type-Options":  "nosniff",
		"Referrer-Policy":         "no-referrer",
		"Content-Security-Policy": "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self'; media-src 'self'; connect-src 'self'",
	}
	// Mount middleware, issue GET, assert each header + handler called
}
```

### 6.2 集成测试（手动）

```bash
cd server
go build -o LocalMediaHub.exe ./cmd/server
./LocalMediaHub.exe --headless &
sleep 3
curl -sI http://localhost:8000/api/v1/health | grep -iE "X-Frame|X-Content|Referrer|Content-Security"
# 期望：4 个头都存在
curl -sI http://localhost:8000/ | grep -iE "X-Frame|X-Content|Referrer|Content-Security"
# 期望：静态资源响应也带 4 个头
kill %1
```

### 6.3 回归测试

- `cd server && go test ./...` 全套件 green
- 现有 Phase 1/3 测试不依赖响应头文本，不受影响

---

## 7. 风险与权衡

| 风险 | 影响 | 缓解 |
|---|---|---|
| CSP 破坏现有 Web UI 渲染 | 中 | 探索阶段确认无外链资源、无 inline script、无 data: URI；集成测试验证一次完整 UI |
| `style-src 'unsafe-inline'` 削弱 CSP | 低 | TODO 注释指向 Phase 5 整改 |
| 中间件顺序错误（CORS 之后）导致 OPTIONS 不带安全头 | 低 | 单元测试覆盖；挂载顺序注释明确 |
| 静态资源响应是否真经过中间件？ | 低 | `s.Echo.GET("/*", echo.WrapHandler(...))` 注册在 Echo 路由内，所有中间件生效 |

---

## 8. 验证完成标准

- ✅ `TestSecurityHeaders` 单元测试通过（4 个头 + handler 调用断言）
- ✅ `curl -I http://localhost:8000/api/v1/health` 显示 4 个安全头
- ✅ `curl -I http://localhost:8000/`（静态资源）显示 4 个安全头
- ✅ Web UI 完整功能正常（手动浏览：连接、看视频、缩略图、tag 管理）
- ✅ `cd server && go test ./...` 全 green（无回归）
- ✅ Phase 1/3 测试无回归

---

## 9. 后续 Phase 衔接

| Phase | 内容 | 备注 |
|---|---|---|
| Phase 2 | libffmpeg.so 审计 + SBOM | 阻断 Chain-D（唯一 RCE 链） |
| Phase 5 | Web UI XSS 整改 + 移除 CSP `'unsafe-inline'` | 与 Phase 4 协同 |
| Phase 6 | 供应链扫描 + 依赖升级 | 独立 |
| Phase 8 | 杂项 P2 | 独立 |

---

## 文档信息

- **创建日期**：2026-07-11
- **审计轮次**：Round 29 Phase 4
- **依赖**：Phase 1（commit `76b6d51`）
- **方法论**：brainstorming skill（澄清 → 方案 → 设计）
- **下一步**：经用户审核后，调用 writing-plans skill 转为实施计划
