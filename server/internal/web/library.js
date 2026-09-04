// 阅读状态 + 收藏（library）模块：纯函数 + API + 列表装饰。
// 注意：不得在模块求值期访问 localStorage（jsdom 测试约束）。

export function applyListFilters(folders, files, decorations, { favoritesOnly, statusFilter }) {
    const favSet = new Set(decorations ? decorations.favorites : []);
    const states = (decorations && decorations.states) || {};
    let outFolders = folders || [];
    let outFiles = files || [];
    if (statusFilter != null) {
        // 状态筛选：仅小说；文件夹与非文本一律隐藏
        outFolders = [];
        outFiles = outFiles.filter(f => {
            if (f.media_type !== 'text') return false;
            const badge = states[f.path];
            const status = badge ? badge.status : 'unread';
            return status === statusFilter;
        });
    }
    if (favoritesOnly) {
        outFolders = outFolders.filter(f => favSet.has(f.path));
        outFiles = outFiles.filter(f => favSet.has(f.path));
    }
    return { folders: outFolders, files: outFiles };
}

export function badgeHtmlFor(status, percent) {
    if (status === 'finished') {
        return '<span class="card-badge card-badge--finished">✓ 已读完</span>'; // XSS-SAFE: 静态常量
    }
    if (status === 'reading') {
        const label = percent > 0 ? `读到 ${percent}%` : '读过';
        return `<span class="card-badge card-badge--reading">${label}</span>`; // XSS-SAFE: label 为受控文案+数字
    }
    return '';
}

// percent = clamp(((chapterIndex + intra) / max(1,totalChapters)) * 100, 0, 100)，1 位小数
export function computeReportPayload({ chapterIndex, paraIndex, chapterParaCount, totalChapters, atChapterEnd }) {
    const intra = chapterParaCount > 0 ? Math.min(1, paraIndex / chapterParaCount) : 0;
    const total = Math.max(1, totalChapters);
    let percent = ((chapterIndex + intra) / total) * 100;
    percent = Math.min(100, Math.max(0, percent));
    percent = Math.round(percent * 10) / 10;
    const finished = totalChapters > 0 && chapterIndex === totalChapters - 1 && Boolean(atChapterEnd);
    if (finished) {
        percent = 100;
    }
    return { percent, finished };
}

export function runWithConcurrency(taskFactories, limit) {
    return new Promise((resolve) => {
        if (!taskFactories || taskFactories.length === 0) {
            return resolve([]);
        }
        const effectiveLimit = Math.max(1, limit || 1);
        let next = 0;
        let active = 0;
        const results = new Array(taskFactories.length);
        const launch = () => {
            if (next >= taskFactories.length && active === 0) {
                return resolve(results);
            }
            while (active < effectiveLimit && next < taskFactories.length) {
                const i = next++;
                active++;
                Promise.resolve()
                    .then(taskFactories[i])
                    .then((res) => {
                        results[i] = res;
                    })
                    .catch(() => {
                        results[i] = undefined;
                    })
                    .then(() => {
                        active--;
                        launch();
                    });
            }
        };
        launch();
    });
}
