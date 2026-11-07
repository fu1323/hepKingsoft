(() => {
    'use strict';

    const NAME = '__hepWordCapture';
    if (window[NAME]) window[NAME].stop();

    const STABLE_MS = 450;     // 同一页 SVG 至少稳定这么久再入队
    const SCAN_INTERVAL_MS = 150;
    const MAX_QUEUE = 16;      // 限制浏览器中尚未消费的 SVG 快照数量

    const candidates = new WeakMap();
    const queued = new Map();       // pageKey -> SVG 快照（尚未被 Java 取走）
    const inFlight = new Map();     // pageKey -> SVG 快照（Java 正在转换/写入）
    const committed = new Map();    // pageKey -> 成功保存时的内容指纹
    let lastMutation = performance.now();
    let lastQueueChange = performance.now();
    let scannedUnits = 0;
    let pendingUnits = 0;
    let unstableUnits = 0;
    let timer = null;
    let scheduled = false;
    let stopped = false;

    // 在滚动容器的祖先链中寻找真正可滚动的元素。
    function scrollRoot() {
        const first = document.querySelector('.canvas-unit');
        let el = first && first.parentElement;
        while (el && el !== document.body && el !== document.documentElement) {
            const style = getComputedStyle(el);
            if (el.scrollHeight > el.clientHeight + 10 &&
                /auto|scroll|overlay/.test(style.overflowY)) return el;
            el = el.parentElement;
        }
        const workspace = document.querySelector('#workspace');
        if (workspace && workspace.scrollHeight > workspace.clientHeight + 10) return workspace;
        return document.scrollingElement || document.documentElement;
    }

    function rootInfo() {
        const el = scrollRoot();
        const top = el.scrollTop || 0;
        const client = el.clientHeight || window.innerHeight;
        const height = el.scrollHeight || client;
        return {
            name: el.tagName.toLowerCase() + (el.id ? '#' + el.id : ''),
            top, clientHeight: client, scrollHeight: height,
            atBottom: top + client >= height - 8,
            canScroll: height > client + 10
        };
    }

    // 双 32 位 FNV 风格散列。仅用于检测变化，不作为加密校验。
    function signature(xml) {
        let a = 2166136261, b = 2246822519;
        for (let i = 0; i < xml.length; i++) {
            const c = xml.charCodeAt(i);
            a = Math.imul(a ^ c, 16777619);
            b = Math.imul(b ^ c, 3266489917);
        }
        return `${(a >>> 0).toString(16)}-${(b >>> 0).toString(16)}-${xml.length}`;
    }

    function pageIdentity(unit, xmlSig) {
        // 优先使用页面在滚动内容中的坐标，避免 data-page-index 恰好是复用槽位编号。
        // 注意：这里返回的是“页面身份”，不是 NodeList 下标。
        const root = scrollRoot();
        if (root && root.contains(unit) && root.scrollHeight > root.clientHeight + 10) {
            const rect = unit.getBoundingClientRect();
            const rootRect = root.getBoundingClientRect();
            const isDocument = root === document.scrollingElement;
            const y = root.scrollTop + rect.top - (isDocument ? 0 : rootRect.top);
            if (Number.isFinite(y) && rect.height > 30) {
                return { key: `pos:${Math.round(y / 20)}`, kind: 'position' };
            }
        }

        const attrs = ['data-page-index', 'data-page-number', 'data-page-no',
                       'data-page-num', 'data-page-id', 'page-index'];
        let el = unit;
        for (let level = 0; el && level < 3; level++, el = el.parentElement) {
            for (const attr of attrs) {
                const value = el.getAttribute?.(attr);
                if (value !== null && value !== undefined && value.trim() !== '') {
                    return { key: `attr:${attr}:${value}`, kind: 'attribute' };
                }
            }
        }

        // 兜底：用内容区分页。完全相同的两页可能被合并，须依赖真实页码解决。
        return { key: `svg:${xmlSig}`, kind: 'fingerprint-fallback' };
    }

    function scan() {
        if (stopped) return;
        const units = document.querySelectorAll('.canvas-unit');
        let waiting = 0, unstable = 0;
        scannedUnits = units.length;
        const now = performance.now();

        for (const unit of units) {
            if (unit.classList.contains('doc-pending-canvas-unit')) {
                waiting++;
                continue;
            }
            const svg = unit.querySelector('svg');
            if (!svg || !svg.children.length) {
                waiting++;
                continue;
            }

            let xml;
            try {
                xml = new XMLSerializer().serializeToString(svg);
            } catch (_) {
                waiting++;
                continue;
            }
            if (!xml.includes('xmlns:xlink')) {
                xml = xml.replace('<svg', '<svg xmlns:xlink="http://www.w3.org/1999/xlink"');
            }
            const w = svg.width?.baseVal?.value || svg.viewBox?.baseVal?.width || svg.getBoundingClientRect().width;
            const h = svg.height?.baseVal?.value || svg.viewBox?.baseVal?.height || svg.getBoundingClientRect().height;
            if (!(w > 0 && h > 0)) {
                waiting++;
                continue;
            }

            const sig = signature(xml);
            let identity = pageIdentity(unit, sig);
            const previous = candidates.get(unit);

            // 如果布局测量稍微抖动，但 SVG 完全不变，不要把它误认成新的一页。
            if (previous && previous.sig === sig &&
                identity.kind === 'position' && previous.kind === 'position' &&
                Math.abs(previous.y - Number(identity.key.slice(4))) <= 10) {
                identity = { key: previous.key, kind: previous.kind };
            }

            const y = identity.kind === 'position' ? Number(identity.key.slice(4)) : 0;
            if (!previous || previous.sig !== sig || previous.key !== identity.key) {
                candidates.set(unit, { key: identity.key, kind: identity.kind,
                                       y, sig, since: now });
                unstable++;
                continue;
            }
            if (now - previous.since < STABLE_MS) {
                unstable++;
                continue;
            }
            if (committed.get(identity.key) === sig) continue;
            if (queued.get(identity.key)?.sig === sig) continue;
            if (inFlight.get(identity.key)?.sig === sig) continue;
            if (queued.size >= MAX_QUEUE) continue;

            queued.set(identity.key, {
                key: identity.key, sig, kind: identity.kind,
                svgStr: xml, w, h
            });
            lastQueueChange = now;
        }
        pendingUnits = waiting;
        unstableUnits = unstable;
    }

    function mutationRelated(record) {
        const target = record.target;
        const element = target.nodeType === Node.ELEMENT_NODE ? target : target.parentElement;
        if (element?.closest?.('.canvas-unit')) return true;
        for (const node of [...record.addedNodes, ...record.removedNodes]) {
            if (node.nodeType !== Node.ELEMENT_NODE) continue;
            if (node.matches?.('.canvas-unit') || node.querySelector?.('.canvas-unit')) return true;
        }
        return false;
    }

    function scheduleScan() {
        if (scheduled || stopped) return;
        scheduled = true;
        setTimeout(() => { scheduled = false; scan(); }, 80);
    }

    const observer = new MutationObserver(records => {
        if (records.some(mutationRelated)) {
            lastMutation = performance.now();
            scheduleScan();
        }
    });
    observer.observe(document.documentElement, {
        subtree: true, childList: true, attributes: true, characterData: true
    });
    timer = setInterval(scan, SCAN_INTERVAL_MS); // 处理 DOM 没变但最初 SVG 刚稳定的情况

    window[NAME] = {
        scan,
        take() {
            const first = queued.entries().next();
            if (first.done) return null;
            const [key, snapshot] = first.value;
            queued.delete(key);
            inFlight.set(key, snapshot);
            return snapshot;
        },
        ack({ key, sig, ok }) {
            const snapshot = inFlight.get(key);
            if (!snapshot || snapshot.sig !== sig) return;
            inFlight.delete(key);
            if (ok) {
                committed.set(key, sig);
            } else if (!queued.has(key)) {
                queued.set(key, snapshot); // 写入失败时允许 Java 再取一次
            }
        },
        drop(key) { // 达到最大重试次数后清理坏任务，不让它阻塞滚动
            const item = inFlight.get(key) || queued.get(key);
            if (item) committed.set(key, item.sig);
            inFlight.delete(key);
            queued.delete(key);
        },
        scrollDown(step) {
            const el = scrollRoot();
            const before = el.scrollTop;
            el.scrollTop = before + Math.max(50, step || 450);
            return { before, after: el.scrollTop, ...rootInfo() };
        },
        toTop() {
            scrollRoot().scrollTop = 0;
            scan();
        },
        status() {
            return {
                ...rootInfo(),
                units: scannedUnits,
                pendingUnits,
                unstableUnits,
                queued: queued.size,
                inFlight: inFlight.size,
                committed: committed.size,
                lastMutationAgoMs: Math.round(performance.now() - lastMutation),
                lastQueueAgoMs: Math.round(performance.now() - lastQueueChange)
            };
        },
        stop() {
            stopped = true;
            observer.disconnect();
            if (timer !== null) clearInterval(timer);
            delete window[NAME];
        }
    };
    scan();
})();
