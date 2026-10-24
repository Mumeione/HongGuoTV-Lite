#!/usr/bin/env node
// 重新生成签名测试向量。参考实现（drpys / GPL-3.0）不入库，需自行提供：
//   node tools/diag/signing-oracle.cjs --source /path/to/hongguo.cjs
// 用法、产出与注意事项见同目录 README.md。
const fs = require('fs');
const vm = require('vm');
const path = require('path');
const crypto = require('crypto');

const at = process.argv.indexOf('--source');
const sourcePath = at > 0 ? process.argv[at + 1] : process.env.HG_SOURCE;
if (!sourcePath || !fs.existsSync(sourcePath)) {
    console.error('用法: node tools/diag/signing-oracle.cjs --source <hongguo.cjs>');
    console.error('（参考实现为 GPL-3.0，见 NOTICE.md；本仓库不内置）');
    process.exit(2);
}

const source = fs.readFileSync(sourcePath, 'utf8');
const root = path.join(__dirname, '..', '..');
const resDir = path.join(root, 'core', 'src', 'test', 'resources');

const ctx = vm.createContext({
    require, Buffer, URL, URLSearchParams, console,
    module: { exports: {} }, exports: {},
});
// 只暴露函数，不修改参考实现的任何一行。
vm.runInContext(
    source + ';globalThis.oracle={branchOf,hashF13,xGorgon,helios,buildMedusa,legacyGorgon,signedVideoRequest,sm3,md5};',
    ctx,
);
const oracle = ctx.oracle;
if (!oracle || typeof oracle.legacyGorgon !== 'function') {
    console.error('参考实现里找不到预期函数，确认路径是否正确');
    process.exit(3);
}

// ── 1. 完整签名套件：12 组固定 ts + 确定性随机源 ────────────────────────────
const rows = [];
for (let variant = 0; variant < 12; variant += 1) {
    const ts = 1800500000 + variant;
    vm.runInContext(
        `Date.now=()=>${ts * 1000}; letSeed=17; Math.random=()=>{letSeed=(Math.imul(letSeed,1664525)+1013904223)>>>0;return letSeed/4294967296;};`,
        ctx,
    );
    const body = variant % 2 === 0 ? Buffer.from('{"video":"1234567890"}') : null;
    let url = '';
    for (let ticket = 0; ticket < 128; ticket += 1) {
        url = `https://example.com/api?q=${encodeURIComponent('短剧')}&ticket=${ticket}`;
        if (oracle.branchOf(url, body, ts) === 0) break;
    }
    const query = url.split('?')[1];
    const md5 = body ? crypto.createHash('md5').update(body).digest() : Buffer.alloc(16);
    // 显式小端，不依赖主机字节序（Uint32Array.of(ts).buffer 在大端机器上会漂）
    const tsLe = Buffer.from([ts & 255, (ts >>> 8) & 255, (ts >>> 16) & 255, (ts >>> 24) & 255]);
    const hash = oracle.hashF13(oracle.sm3(query), md5, tsLe, ts).toString('hex');
    rows.push({
        ts,
        url,
        body: body ? body.toString() : null,
        hash,
        gorgon: oracle.xGorgon(query, body, ts, 42131),
        helios: oracle.helios(ts),
        medusa: oracle.buildMedusa(url, body, ts),
    });
}
fs.writeFileSync(path.join(resDir, 'signing.json'), JSON.stringify(rows, null, 2) + '\n');

// ── 3. 来源追溯（放在 tools 下，不进测试资源；不写时间戳以保证重跑无 diff）────────
const sha256 = crypto.createHash('sha256').update(source).digest('hex');
fs.writeFileSync(
    path.join(__dirname, 'signing.provenance.json'),
    JSON.stringify({
        sourceFile: path.basename(sourcePath),
        sourceSha256: sha256,
        note: '参考实现为 drpys (GPL-3.0)，不随仓库分发。重新生成见 tools/diag/README.md。',
    }, null, 2) + '\n',
);

console.log(`✓ signing.json ${rows.length} 组（完整套件）`);
console.log(`✓ 来源 sha256  ${sha256.slice(0, 16)}…`);
