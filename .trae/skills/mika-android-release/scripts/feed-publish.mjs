#!/usr/bin/env node
/**
 * feed-publish.mjs · 生成 / 校验 / 发布 / 回滚「如愿」安卓端的静态更新 feed（latest.json）
 *
 * feed 是客户端更新的唯一事实源，托管在 desktop 同域的固定 URL：
 *   https://ai.thchengtay.com/desktop/xxt/ruyi/android/latest.json
 * apphub 只做台账登记（元数据 + 直链），客户端不读它。
 *
 * 四个子命令：
 *   generate  读本地 APK 算 sha256/size，组装 latest.json，写本地文件；prod 可选原子上传 + HTTP 回读
 *   validate  只校验本地 feed 文件格式（+ 可选 HTTP 回读比对），不写不传
 *   rollback  用线上 latest.json.bak 回退当前 feed
 *   show      打印一份 feed 样例结构（写脚本时对齐字段用）
 *
 * 用法：
 *   node feed-publish.mjs generate \
 *     --version 0.1.51 --code 52 \
 *     --url "https://oss-chuangxin.thchengtay.com/ai/ruyi/android/ruyi-0.1.51-universal.apk" \
 *     --file /abs/path/app-release.apk --changelog "新增…\n修复…" \
 *     --feed-url https://ai.thchengtay.com/desktop/xxt/ruyi/android/latest.json \
 *     --out /abs/path/.local/feed/latest.json \
 *     [--policy force] [--dry-run] \
 *     [--no-upload | --server user@host --root /www/wwwroot/xxt-desktop --rel ruyi/android/latest.json --verify-url <url>]
 *
 *   node feed-publish.mjs validate --out <本地 feed> [--verify-url <url>]
 *   node feed-publish.mjs rollback [--server user@host --root /www/wwwroot/xxt-desktop --rel ruyi/android/latest.json] [--dry-run]
 *
 * 安全：--server/--root/--rel 白名单校验后才进 shell；ssh/scp 用 execFileSync 参数数组拼装，
 *       不经过本地 shell；远端命令只由校验过的值组成。上传走 stage 临时文件 + 原子 mv，
 *       上传前备份线上旧 feed 为 latest.json.bak，失败自动回滚。
 *
 * 退出码：0 成功 / 2 配置或用法错 / 3 文件或格式错 / 4 上传失败 / 5 校验失败
 */
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { createReadStream, existsSync, mkdirSync, readFileSync, renameSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join, posix, resolve, normalize } from 'node:path';

// ---------------------------------------------------------------------------
// 参数解析
// ---------------------------------------------------------------------------
function parseArgs(argv) {
  const out = { _: [] };
  for (let i = 0; i < argv.length; i += 1) {
    const token = argv[i];
    if (!token.startsWith('--')) {
      out._.push(token);
      continue;
    }
    const key = token.slice(2);
    const next = argv[i + 1];
    if (next === undefined || next.startsWith('--')) out[key] = true;
    else {
      out[key] = next;
      i += 1;
    }
  }
  return out;
}

const args = parseArgs(process.argv.slice(2));
const CMD = args._[0] || 'generate';
const DRY_RUN = Boolean(args['dry-run']);
const NO_UPLOAD = Boolean(args['no-upload']);

function log(step, message) {
  console.log(`${DRY_RUN ? 'DRY ' : '    '}[${step}] ${message}`);
}
function warn(message) {
  console.log(`WARN ${message}`);
}
function fail(message, code = 2) {
  console.error(`ERR [失败] ${message}`);
  process.exit(code);
}

if (!['generate', 'validate', 'rollback', 'show'].includes(CMD)) {
  fail(`未知子命令 "${CMD}"。可用：generate | validate | rollback | show`, 2);
}

// ---------------------------------------------------------------------------
// 远端配置（上传 / 回滚用）
// ---------------------------------------------------------------------------
const SERVER = String(args.server || '').trim();
// desktop 站点 web 根：nginx 只对 /desktop/xxt/ 前缀开放对外访问。
// 早先的 /www/wwwroot/xxt-desktop（公网 /ruyi/...）实测 404，已弃用。
const ROOT = String(args.root || '/opt/desktop-deploy/www/desktop/xxt').trim();
const REL = String(args.rel || 'ruyi/android/latest.json').trim();
const VERIFY_URL = String(args['verify-url'] || '').trim();

const SERVER_RE = /^[\w.@-]+$/;
const ROOT_RE = /^\/[\w./-]+$/;
const REL_RE = /^[\w./-]+$/;

function assertRemoteConfig() {
  if (!SERVER) fail('上传需要 --server user@host（或环境变量 FEED_SERVER）。--no-upload 可只生成本地文件。', 2);
  if (!SERVER_RE.test(SERVER)) fail(`--server 非法："${SERVER}"（只允许字母数字 . _ @ -）`, 2);
  if (!ROOT_RE.test(ROOT)) fail(`--root 非法："${ROOT}"（须为无空格无引号的绝对路径）`, 2);
  if (!REL_RE.test(REL)) fail(`--rel 非法："${REL}"（只允许字母数字 . _ / -）`, 2);
  // 规范化后不得逃出 root。
  const norm = normalize(REL).replace(/^(\.\/)+/, '');
  if (norm.startsWith('..') || posix.isAbsolute(norm)) fail(`--rel 不得包含 .. 或绝对路径："${REL}"`, 2);
  return norm;
}

const remoteTarget = () => `${ROOT.replace(/\/$/, '')}/${REL.replace(/^\/+/, '')}`;
const remoteStage = () => `${ROOT.replace(/\/$/, '')}/.uploads/ruyi-android-feed/latest.json`;
const remoteBackup = () => `${remoteTarget()}.bak`;

// ---------------------------------------------------------------------------
// ssh / scp：只走参数数组，不经本地 shell
// ---------------------------------------------------------------------------
function sshRun(cmd, opts = {}) {
  console.log(`+ ssh ${SERVER} ${cmd}`);
  if (DRY_RUN) return '';
  return execFileSync('ssh', [SERVER, cmd], {
    stdio: ['ignore', 'pipe', 'inherit'],
    encoding: 'utf8',
    ...opts,
  });
}
function scpTo(localPath, remotePath) {
  console.log(`+ scp ${localPath} ${SERVER}:${remotePath}`);
  if (DRY_RUN) return;
  execFileSync('scp', [localPath, `${SERVER}:${remotePath}`], { stdio: ['ignore', 'pipe', 'inherit'] });
}
function sha256OfFile(path) {
  return new Promise((resolveHash, reject) => {
    const hash = createHash('sha256');
    const stream = createReadStream(path);
    stream.on('data', (chunk) => hash.update(chunk));
    stream.on('end', () => resolveHash(hash.digest('hex')));
    stream.on('error', reject);
  });
}
async function sha256OfText(text) {
  return createHash('sha256').update(text).digest('hex');
}
function httpGetText(url) {
  // 本机走 TUN 代理，curl 必须 --noproxy '*' 才能拿到真实内容。
  return execFileSync('curl', ['--noproxy', '*', '-fsS', '--max-time', '20', url], {
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'ignore'],
  });
}

// ---------------------------------------------------------------------------
// feed 字段校验（本地格式）
// ---------------------------------------------------------------------------
function checkFeedObject(feed) {
  const problems = [];
  if (typeof feed.version !== 'string' || !feed.version.trim()) problems.push('version 缺失或为空');
  if (!Number.isInteger(feed.versionCode) || feed.versionCode < 1) problems.push('versionCode 非法');
  if (typeof feed.changelog !== 'string') problems.push('changelog 非字符串');
  if (typeof feed.publishedAt !== 'string' || feed.publishedAt.length < 10) problems.push('publishedAt 缺失');
  if (!feed.download || typeof feed.download !== 'object') problems.push('download 缺失');
  else {
    if (typeof feed.download.url !== 'string' || !/^https?:\/\//.test(feed.download.url)) problems.push('download.url 非法');
    if (!Number.isInteger(feed.download.size) || feed.download.size <= 0) problems.push('download.size 非法');
    if (typeof feed.download.sha256 !== 'string' || !/^[0-9a-f]{64}$/i.test(feed.download.sha256)) {
      problems.push('download.sha256 非法（须 64 位 hex）');
    }
  }
  if (feed.policy !== undefined && !['optional', 'force'].includes(feed.policy)) problems.push('policy 非法');
  return problems;
}

// ---------------------------------------------------------------------------
// 子命令：generate
// ---------------------------------------------------------------------------
async function cmdGenerate() {
  const version = String(args.version || '').trim();
  const codeRaw = String(args.code || '').trim();
  const url = String(args.url || '').trim();
  const apkFile = String(args.file || '').trim();
  const changelog = String(args.changelog ?? '');
  const feedUrl = String(args['feed-url'] || '').trim();
  const outPath = args.out && args.out !== true ? resolve(String(args.out)) : '';
  const policy = String(args.policy || 'optional').trim();

  const missing = [];
  if (!version) missing.push('--version');
  if (!/^\d+$/.test(codeRaw)) missing.push('--code(整数)');
  if (!/^https?:\/\//.test(url)) missing.push('--url(合法 http(s))');
  if (!apkFile) missing.push('--file');
  if (!outPath) missing.push('--out');
  if (missing.length) fail(`generate 缺少或非法参数：${missing.join(', ')}`, 2);
  if (!existsSync(apkFile)) fail(`找不到本地 APK：${apkFile}`, 3);
  if (!['optional', 'force'].includes(policy)) fail(`--policy 只能是 optional 或 force，收到 "${policy}"`, 2);
  if (version.includes('/') || version.includes('..')) fail(`--version 非法："${version}"`, 2);

  const code = Number(codeRaw);
  const size = statSync(apkFile).size;
  if (size <= 0) fail(`APK 文件大小异常：${size}`, 3);
  const sha256 = await sha256OfFile(apkFile);

  // publishedAt：默认当前时间，可用 MIKA_PUBLISHED_AT 覆盖以便复现。
  const publishedAt = String(process.env.MIKA_PUBLISHED_AT || new Date().toISOString());

  const feed = {
    version,
    versionCode: code,
    publishedAt,
    changelog: changelog.trim(),
    policy,
    download: { url, size, sha256 },
  };

  const problems = checkFeedObject(feed);
  if (problems.length) fail(`生成的 feed 未通过格式校验：${problems.join('; ')}`, 3);

  const text = `${JSON.stringify(feed, null, 2)}\n`;
  log('feed', `version=${version} code=${code} size=${size} sha256=${sha256.slice(0, 12)}…`);
  log('feed', `feed-url=${feedUrl || '(未指定)'}`);

  if (DRY_RUN) {
    console.log('\n--- DRY-RUN：仅预演，未写文件、未上传 ---');
    console.log(text);
    console.log('=== DRY-RUN 完成 ===');
    return;
  }

  // 写本地文件：先 .tmp 再原子 rename。
  mkdirSync(dirname(outPath), { recursive: true });
  const tmpPath = `${outPath}.tmp`;
  writeFileSync(tmpPath, text, 'utf8');
  renameSync(tmpPath, outPath);
  log('feed', `已写本地 feed：${outPath}`);

  if (NO_UPLOAD) {
    log('上传', '已跳过（--no-upload）：feed 仅落本地，未发布到服务端。');
    return;
  }

  // 上传：白名单校验 → 建 stage → 备份线上 → scp → 校验 → 原子 mv → HTTP 回读。
  const rel = assertRemoteConfig();
  const target = remoteTarget();
  const stage = remoteStage();
  const backup = remoteBackup();
  const stageDir = posix.dirname(stage);
  const targetDir = posix.dirname(target);

  sshRun(`mkdir -p ${stageDir} ${targetDir}`);
  const localHash = await sha256OfText(text);

  // 线上现状：有旧 feed 且与本次不同则先备份，供失败回滚。
  let hadOnline = false;
  try {
    const onlineHash = sshRun(`test -f ${target} && sha256sum ${target} | awk '{print $1}' || true`).trim();
    if (/^[0-9a-f]{64}$/i.test(onlineHash)) {
      hadOnline = true;
      if (onlineHash !== localHash) {
        sshRun(`cp -f ${target} ${backup}`);
        log('上传', `已备份线上旧 feed → ${backup}`);
      } else {
        log('上传', '线上 feed 与本次内容一致，跳过备份与切换。');
        if (VERIFY_URL) await verifyRemote(text);
        return;
      }
    }
  } catch (error) {
    fail(`读取线上 feed 状态失败：${error.message}`, 4);
  }

  scpTo(outPath, stage);
  const stagedHash = sshRun(`sha256sum ${stage} | awk '{print $1}'`).trim();
  if (stagedHash !== localHash) {
    fail(`stage 文件校验不符（本地 ${localHash} / 远端 ${stagedHash}），已中止切换。`, 4);
  }
  log('上传', `stage 校验通过：${stage}`);

  // 原子切换；mv 失败则尝试回滚。
  try {
    sshRun(`mv ${stage} ${target}`);
  } catch (error) {
    warn(`原子切换失败，尝试回滚：${error.message}`);
    tryRollback(hadOnline, backup, target);
    fail('feed 切换失败并已尝试回滚。', 4);
  }
  log('上传', `已原子切换到 ${target}`);

  if (VERIFY_URL) await verifyRemote(text, hadOnline, backup, target);
  console.log('\n===== feed 发布完成 =====');
  console.log(`本地：${outPath}`);
  console.log(`远端：${SERVER}:${target}`);
  console.log(`sha256：${localHash}`);
}

async function verifyRemote(expectedText, hadOnline, backup, target) {
  log('校验', `HTTP 回读 ${VERIFY_URL}`);
  try {
    const live = httpGetText(VERIFY_URL);
    const liveHash = await sha256OfText(live.replace(/\r?\n$/, ''));
    const expHash = await sha256OfText(expectedText.replace(/\r?\n$/, ''));
    if (liveHash !== expHash) throw new Error('线上内容与本地 feed 不一致');
    log('校验', 'HTTP 回读一致。');
  } catch (error) {
    warn(`HTTP 回读失败：${error.message}`);
    if (hadOnline) tryRollback(true, backup, target);
    fail('feed 已切换但 HTTP 校验未通过，已尝试回滚。请人工核对。', 5);
  }
}

function tryRollback(hadOnline, backup, target) {
  if (!hadOnline) {
    warn('线上原本没有 feed，无备份可回滚；请人工处理。');
    return;
  }
  try {
    sshRun(`mv -f ${backup} ${target}`);
    log('回滚', `已从 ${backup} 回退到 ${target}`);
  } catch (error) {
    warn(`自动回滚失败：${error.message}。请手动执行：ssh ${SERVER} 'mv -f ${backup} ${target}'`);
  }
}

// ---------------------------------------------------------------------------
// 子命令：validate
// ---------------------------------------------------------------------------
async function cmdValidate() {
  const outPath = args.out && args.out !== true ? resolve(String(args.out)) : '';
  if (!outPath) fail('validate 需要 --out <本地 feed 路径>', 2);
  if (!existsSync(outPath)) fail(`找不到 feed 文件：${outPath}`, 3);
  let feed;
  try {
    feed = JSON.parse(readFileSync(outPath, 'utf8'));
  } catch (error) {
    fail(`feed 不是合法 JSON：${error.message}`, 3);
  }
  const problems = checkFeedObject(feed);
  if (problems.length) fail(`feed 格式校验未通过：${problems.join('; ')}`, 3);
  log('校验', `本地 feed 格式 OK：version=${feed.version} code=${feed.versionCode}`);

  if (VERIFY_URL) {
    const verifyUrl = String(VERIFY_URL);
    const expectedText = readFileSync(outPath, 'utf8');
    try {
      const live = httpGetText(verifyUrl);
      const liveHash = await sha256OfText(live.replace(/\r?\n$/, ''));
      const expHash = await sha256OfText(expectedText.replace(/\r?\n$/, ''));
      if (liveHash !== expHash) throw new Error('线上内容与本地不一致');
      log('校验', `HTTP 回读一致：${verifyUrl}`);
    } catch (error) {
      fail(`HTTP 回读校验失败：${error.message}`, 5);
    }
  }
  console.log('RESULT: feed validate OK');
}

// ---------------------------------------------------------------------------
// 子命令：rollback
// ---------------------------------------------------------------------------
function cmdRollback() {
  const rel = assertRemoteConfig();
  void rel;
  const target = remoteTarget();
  const backup = remoteBackup();
  log('回滚', `${SERVER}:${backup} -> ${target}`);
  tryRollback(true, backup, target);
  console.log('RESULT: rollback attempted（请以 HTTP 回读为准）');
}

// ---------------------------------------------------------------------------
// 子命令：show
// ---------------------------------------------------------------------------
function cmdShow() {
  const sample = {
    version: '0.1.51',
    versionCode: 52,
    publishedAt: '2026-11-16T12:00:00.000Z',
    changelog: '### 新增\n- 新功能\n### 修复\n- 若干问题',
    policy: 'optional',
    download: {
      url: 'https://oss-chuangxin.thchengtay.com/ai/ruyi/android/ruyi-0.1.51-universal.apk',
      size: 43891234,
      sha256: 'a'.repeat(64),
    },
  };
  console.log(JSON.stringify(sample, null, 2));
}

// ---------------------------------------------------------------------------
// 入口
// ---------------------------------------------------------------------------
await (async () => {
  if (CMD === 'generate') return cmdGenerate();
  if (CMD === 'validate') return cmdValidate();
  if (CMD === 'rollback') return cmdRollback();
  if (CMD === 'show') return cmdShow();
  fail(`未知子命令：${CMD}`, 2);
})();
