// 视频播放控制纯函数：倍速循环、滚轮→音量。无 DOM 依赖，便于单测。

// 返回档位列表中当前值的下一档；末档循环回首档。
// 当前值不在档位中时，从默认 1x 的下一档开始（容错）。
export function nextSpeed(currentRate, speeds) {
    const i = speeds.indexOf(currentRate);
    if (i === -1) {
        const def = speeds.indexOf(1);
        return speeds[(def + 1) % speeds.length];
    }
    return speeds[(i + 1) % speeds.length];
}

// 滚轮 deltaY → 新音量，钳制到 [0, 1]。
// deltaY < 0（向上滚）音量 +step；deltaY > 0（向下滚）音量 -step。
export function wheelToVolume(currentVolume, deltaY, step) {
    const next = deltaY < 0 ? currentVolume + step : currentVolume - step;
    return Math.min(1, Math.max(0, next));
}
