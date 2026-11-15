#!/usr/bin/env node
/**
 * mika-android-release · 一键发布「如愿(Mika)」安卓包到 app-version-hub
 *
 * 流水线：读版本 → gradlew 构建 → ossutil 上传 OSS → apphub 登记 → 回读校验。
 *
 * 依赖两个既有工具（本脚本只做编排，不重复实现它们的能力）：
 *   - ossutil 2.x         阿里云 OSS 命令行（负责把 APK 传到 chuangxin-prod 桶）
 *   - apphub-publish.mjs   app-version-hub 自带发布 CLI（负责登记版本 + 回读）
 *
 * 环境变量（凭证一律走环境变量，不落仓库）：
 *   APPHUB_BASE_URL   apphub 服务地址（生产带 /apphub 前缀，本地 http://127.0.0.1:3100）
 *   APPHUB_USERNAME   发布方账号（role=publisher，如 mika-release）
 *   APPHUB_PASSWORD   该账号口令
 *   APPHUB_CLI        apphub-publish.mjs 路径（默认按已知仓库位置自动探测）
 *
 * OSS（凭证由 ossutil 自身的 ~/.ossutilconfig 或 ALICLOUD_* 环境变量提供，本脚本不接管）：
 *   OSS_BUCKET      默认 chuangxin-prod
 *   OSS_REGION      默认 cn-hangzhou
 *   OSS_ENDPOINT    默认 https://oss-cn-hangzhou.aliyuncs.com（上传用内网/标准 endpoint）
 *   OSS_PREFIX      对象键前缀，默认 ai/mika/android
 *   OSS_PUBLIC_BASE 下载域名，默认 https://oss-chuangxin.thchengtay.com（登记进 apphub 的直链）
 *   OSSUTIL         ossutil 可执行文件，默认 PATH 里的 ossutil
 *
 * 可选环境变量：
 *   MIKA_ROOT        项目根（含 gradlew 与 app/build.gradle.kts），默认 process.cwd()
 *   APP_NAME         应用显示名，默认「如愿」
 *   MIKA_CHANGELOG   变更说明（也可用 --changelog 覆盖）
 *
 * 用法：
 *   node release.mjs                          # 完整流水线
 *   node release.mjs --skip-build             # 已有 APK，跳过构建
 *   node release.mjs --dry-run                # 只打印将做的事，不构建/上传/登记
 *   node release.mjs --abi arm64-v8a          # 发布 arm64 小包（默认 universal 全架构包）
 *   node release.mjs --url http://127.0.0.1:3100/t.apk   # 跳过 OSS 上传，用指定直链登记（本地联调）
 *   node release.mjs --changelog "修复若干问题"
 *   node release.mjs --channel beta --status draft
 *
 * 退出码：0 成功 / 1 用法或未知错误 / 2 配置缺失 / 3 构建失败 / 4 上传失败 / 5 登记失败
 */
import { execSync } from 'node:child_process';
import { readFileSync, existsSync } from 'node:fs';
import { join, resolve } from 'node:path';

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
    if (next === undefined || next.startsWith('--')) {
      out[key] = true;
    } else {
      out[key] = next;
      i += 1;
    }
  }
  return out;
}

const args = parseArgs(process.argv.slice(2));
const DRY_RUN = Boolean(args['dry-run']);
const SKIP_BUILD = Boolean(args['skip-build']);

function log(step, message) {
  const prefix = DRY_RUN ? 'DRY ' : '    ';
  console.log(`${prefix}[${step}] ${message}`);
}

function fail(message, code = 1) {
  console.error(`ERR [失败] ${message}`);
  process.exit(code);
}

function sh(cmd, opts = {}) {
  log('执行', cmd);
  if (DRY_RUN && opts.skipOnDryRun !== false) return '';
  return execSync(cmd, { stdio: 'inherit', ...opts });
}

// ---------------------------------------------------------------------------
// 配置
// ---------------------------------------------------------------------------
const ROOT = resolve(String(process.env.MIKA_ROOT || process.cwd()));
const BUILD_FILE = join(ROOT, 'app', 'build.gradle.kts');
if (!existsSync(BUILD_FILE)) {
  fail(`找不到 app/build.gradle.kts：${BUILD_FILE}。请在 Mika 项目根目录运行，或用 MIKA_ROOT 指定。`, 2);
}

const APPHUB_BASE_URL = String(process.env.APPHUB_BASE_URL || 'http://127.0.0.1:3100').replace(/\/+$/, '');
const APPHUB_USERNAME = String(process.env.APPHUB_USERNAME || '');
const APPHUB_PASSWORD = String(process.env.APPHUB_PASSWORD || '');

const APP_NAME = String(process.env.APP_NAME || '如愿');
const CHANNEL = String(args.channel || process.env.MIKA_CHANNEL || 'official');
const STATUS = String(args.status || 'published');
const ABI = String(args.abi || process.env.MIKA_ABI || 'universal');
const CHANGELOG = String(args.changelog ?? process.env.MIKA_CHANGELOG ?? '');
const MIN_SUPPORTED = args['min-supported'] || process.env.MIKA_MIN_SUPPORTED || '0';

const OSS_BUCKET = String(process.env.OSS_BUCKET || 'chuangxin-prod');
const OSS_REGION = String(process.env.OSS_REGION || 'cn-hangzhou');
const OSS_ENDPOINT = String(process.env.OSS_ENDPOINT || 'https://oss-cn-hangzhou.aliyuncs.com');
const OSS_PREFIX = String(process.env.OSS_PREFIX || 'ai/mika/android').replace(/^\/+|\/+$/g, '');
const OSS_PUBLIC_BASE = String(process.env.OSS_PUBLIC_BASE || 'https://oss-chuangxin.thchengtay.com').replace(/\/+$/, '');
const OSSUTIL = String(process.env.OSSUTIL || 'ossutil');

// apphub CLI 路径：优先环境变量，其次已知仓库位置
const APPHUB_CLI = String(
  process.env.APPHUB_CLI ||
    '/Users/binny/workspace-sdd/mcp-demo/dsh/app-version-hub/scripts/apphub-publish.mjs'
);

// ---------------------------------------------------------------------------
// 从 build.gradle.kts 读版本事实源（applicationId / versionName / versionCode）
// ---------------------------------------------------------------------------
function readBuildMeta() {
  const src = readFileSync(BUILD_FILE, 'utf8');
  const grab = (re, label) => {
    const m = src.match(re);
    if (!m) fail(`无法从 build.gradle.kts 解析 ${label}`, 2);
    return m[1];
  };
  const appKey = grab(/applicationId\s*=\s*"([^"]+)"/, 'applicationId');
  const versionName = grab(/val\s+ruyiVersionName\s*=\s*"([^"]+)"/, 'ruyiVersionName');
  const versionCode = Number(grab(/val\s+ruyiVersionCode\s*=\s*(\d+)/, 'ruyiVersionCode'));
  if (!Number.isInteger(versionCode) || versionCode < 1) {
    fail(`versionCode 解析异常：${versionCode}`, 2);
  }
  return { appKey, versionName, versionCode };
}

// ---------------------------------------------------------------------------
// 前置检查
// ---------------------------------------------------------------------------
function preflight() {
  if (!APPHUB_USERNAME || !APPHUB_PASSWORD) {
    fail('缺少 APPHUB_USERNAME / APPHUB_PASSWORD（发布方账号口令），请用环境变量注入。', 2);
  }
  if (!existsSync(APPHUB_CLI)) {
    fail(`找不到 apphub-publish.mjs：${APPHUB_CLI}。请用 APPHUB_CLI 指定。`, 2);
  }
}

// ---------------------------------------------------------------------------
// 主流程
// ---------------------------------------------------------------------------
const meta = readBuildMeta();
const apkFileName = `mika-${meta.versionName}-${ABI}.apk`;
const apkLocal = join(ROOT, 'app', 'build', 'outputs', 'apk', 'release', `如愿-${meta.versionName}-${ABI}-release.apk`);
const objectKey = `${OSS_PREFIX}/${apkFileName}`;
// --url 给了就跳过 OSS 上传，直接用给定的直链登记（本地联调 / 包已在别处托管时用）
const URL_OVERRIDE = args.url ? String(args.url) : '';
const downloadUrl = URL_OVERRIDE || `${OSS_PUBLIC_BASE}/${objectKey}`;

log('信息', `appKey=${meta.appKey} 应用名=${APP_NAME}`);
log('信息', `versionName=${meta.versionName} versionCode=${meta.versionCode} ABI=${ABI} channel=${CHANNEL} status=${STATUS}`);
log('信息', `APK 本地路径=${apkLocal}`);
log('信息', URL_OVERRIDE ? 'OSS 上传=跳过（--url 已指定直链）' : `OSS 对象键=oss://${OSS_BUCKET}/${objectKey}`);
log('信息', `下载直链=${downloadUrl}`);
log('信息', `apphub=${APPHUB_BASE_URL} 账号=${APPHUB_USERNAME}`);

if (DRY_RUN) {
  console.log('\nDRY 以上为将要执行的动作，未做任何实际构建 / 上传 / 登记。');
  process.exit(0);
}

preflight();

// 1) 构建
if (!SKIP_BUILD) {
  log('构建', ':app:assembleRelease');
  sh(`${join(ROOT, 'gradlew')} :app:assembleRelease -PapphubBaseUrl="${APPHUB_BASE_URL}"`, { cwd: ROOT });
}
if (!existsSync(apkLocal)) {
  fail(`未找到产物 APK：${apkLocal}。构建是否成功？或用 --abi 指定其他架构。`, 3);
}

// 2) 上传 OSS（给了 --url 则跳过）
if (URL_OVERRIDE) {
  log('上传', '已跳过 OSS 上传（使用 --url 指定的直链）');
} else {
  log('上传', `ossutil cp ${apkLocal} -> oss://${OSS_BUCKET}/${objectKey}`);
  sh(
    `${OSSUTIL} cp "${apkLocal}" "oss://${OSS_BUCKET}/${objectKey}" --region ${OSS_REGION} -e ${OSS_ENDPOINT} --force`,
    { cwd: ROOT }
  );
}

// 3) 确保应用已注册（幂等）
log('登记', 'apphub app:ensure');
sh(
  `node "${APPHUB_CLI}" app:ensure --key ${meta.appKey} --name "${APP_NAME}" --platforms android`,
  {
    cwd: ROOT,
    env: { ...process.env, APPHUB_BASE_URL, APPHUB_USERNAME, APPHUB_PASSWORD },
  }
);

// 4) 登记版本（--code 用 build.gradle 的 versionCode，单一事实源；--file 算 sha256/size）
log('登记', 'apphub publish');
sh(
  `node "${APPHUB_CLI}" publish ` +
    `--key ${meta.appKey} --platform android --version ${meta.versionName} ` +
    `--code ${meta.versionCode} --channel ${CHANNEL} --status ${STATUS} ` +
    `--url "${downloadUrl}" --file "${apkLocal}" ` +
    `--min-supported ${MIN_SUPPORTED} --changelog "${CHANGELOG}"`,
  {
    cwd: ROOT,
    env: { ...process.env, APPHUB_BASE_URL, APPHUB_USERNAME, APPHUB_PASSWORD },
  }
);

// 5) 结果汇报
console.log('\n===== 发布完成 =====');
console.log(`应用：${APP_NAME}（${meta.appKey}）`);
console.log(`版本：v${meta.versionName}（code=${meta.versionCode}）android/${CHANNEL}/${STATUS}`);
console.log(`下载：${downloadUrl}`);
console.log(
  `检查更新：${APPHUB_BASE_URL}/api/v1/update/check?appKey=${meta.appKey}&platform=android&channel=${CHANNEL}`
);
