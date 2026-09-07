// 纯逻辑阅读计时器（spec 2026-09-08-reading-stats-and-android-bookshelf）：
// start/stop 括起活跃阅读时段，takeDelta 取走并清零已累积秒数（运行中取走不停止
// 计时）。时钟注入（now 返回毫秒）便于 node:test；生产默认 Date.now。
// 活跃定义由调用方维护：textReader 在 document.visibilityState !== 'visible'
// 或阅读器关闭时调用 stop。
export function createReadingTimer(now = () => Date.now()) {
    let startTs = null;
    let pendingSec = 0;
    return {
        start() { if (startTs === null) startTs = now(); },
        stop() {
            if (startTs !== null) {
                pendingSec += Math.floor((now() - startTs) / 1000);
                startTs = null;
            }
        },
        takeDelta() {
            let d = pendingSec;
            pendingSec = 0;
            if (startTs !== null) {
                d += Math.floor((now() - startTs) / 1000);
                startTs = now();
            }
            return d;
        },
        isRunning() { return startTs !== null; },
    };
}
