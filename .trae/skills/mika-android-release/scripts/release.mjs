#!/usr/bin/env node
/**
 * mika-android-release · 一键发布「如意 AI(Mika)」安卓包到 app-version-hub
 *
 * 流水线：读版本 → 按环境构建 APK → 签名校验(prod) → ossutil 上传 OSS → apphub 登记 → 回读。
 *
 * 环境是必填项，且一次决定五件事（构建类型 / 签名要求 / OSS 前缀 / 默认渠道与状态 / 目标库）：
 *   --env prod  : assembleRelease + 必须正式签名 + OSS ai/ruyi/android + official/published + 生产库
 *   --env dev   : assemblePerf -PsideInstall(.perf 并排安装) + 允许调试签名
 *                 + OSS ai/ruyi/android/dev + beta/draft + 本地库
 *
 * 依赖两个既有工具（本脚本只做编排，不重复实现它们的能力）：
 *   - ossutil 2.x         阿里云 OSS 命令行（负责把 APK 传到 chuangxin-prod 桶）
 *   - apphub-publish.mjs   app-version-hub 自带发布 CLI（负责登记版本 + 回读）
 *
 * 环境变量（凭证一律走环境变量，不落仓库）：
 *   APPHUB_PROD_BASE_URL  prod 地址的最高优先级。形如 https://<PUBLIC_DOMAIN>/apphub（带 /apphub 前缀，
 *                         不是 http://<APP_HOST>:3110 那种集群内地址）。未提供时依次回落：
 *                         --account 指向的账号文件 → ~/.config/apphub/agents/*.env 的一致值。脚本不内置域名。
 *   APPHUB_BASE_URL       dev 用（默认 http://127.0.0.1:3100）；prod 仅在未给 PROD 变量时回落。
 *   APPHUB_PROD_USERNAME / APPHUB_PROD_PASSWORD   prod 发布方账号（role=publisher）
 *   APPHUB_USERNAME / APPHUB_PASSWORD             dev 发布方账号，prod 时作为回落
 *   APPHUB_CLI            apphub-publish.mjs 路径（默认按已知仓库位置自动探测）
 *   MIKA_RELEASE_ENV      等价于 --env，命令行优先
 *
 * OSS（凭证由 ossutil 自身的 ~/.ossutilconfig 提供，或用 OSS_ACCESS_KEY_ID/OSS_ACCESS_KEY_SECRET；
 * 注意 ossutil 2.x 不认 ALICLOUD_* / ALIBABA_CLOUD_* / ALIYUN_OSS_*，本脚本不接管凭证）：
 *   OSS_BUCKET      默认 chuangxin-prod
 *   OSS_REGION      默认 cn-hangzhou
 *   OSS_ENDPOINT    默认 https://oss-cn-hangzhou.aliyuncs.com
 *   OSS_PREFIX      对象键前缀，dev 默认 ai/ruyi/android/dev，prod 默认 ai/ruyi/android
 *   OSS_PUBLIC_BASE 下载域名，默认 https://oss-chuangxin.thchengtay.com（登记进 apphub 的直链）
 *   OSSUTIL         ossutil 可执行文件，默认 PATH 里的 ossutil
 *
 * 其他可选环境变量：
 *   MIKA_ROOT                 项目根（含 gradlew 与 app/build.gradle.kts），默认 process.cwd()
 *   APP_NAME                  应用显示名，默认「如意 AI」
 *   MIKA_CHANGELOG            变更说明（也可用 --changelog 覆盖）
 *   APKSIGNER                 apksigner 路径（prod 签名校验用，默认自动探测 ANDROID_HOME/build-tools）
 *   MIKA_EXPECTED_SIGNER_CN   期望的签名者 CN，默认 Yuan AI
 *
 * 用法：
 *   node release.mjs --env dev --changelog "本地联调"            # 本地库 / beta / draft / .perf 包
 *   node release.mjs --env prod --account agent-01 --dry-run      # 生产预演（地址与账号取自账号文件）
 *   node release.mjs --env prod --account agent-01 --yes          # 生产对外发布
 *   node release.mjs --env dev --skip-build                      # 已有 APK，跳过构建
 *   node release.mjs --env dev --url http://127.0.0.1:8080/t.apk # 跳过 OSS，用指定直链登记
 *   node release.mjs --env prod --abi arm64-v8a --yes            # 发布 arm64 小包
 *
 * 退出码：0 成功 / 1 用法或未知错误 / 2 配置缺失·环境非法·闸门未过 / 3 构建失败 / 4 上传失败 / 5 登记失败
 */
import { execSync } from 'node:child_process';
import { readFileSync, existsSync, readdirSync } from 'node:fs';
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
const CONFIRMED = Boolean(args.yes);

function log(step, message) {
  const prefix = DRY_RUN ? 'DRY ' : '    ';
  console.log(`${prefix}[${step}] ${message}`);
}

function warn(message) {
  console.log(`WARN ${message}`);
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

function trimEndSlash(value) {
  return String(value).replace(/\/+$/, '');
}

// ---------------------------------------------------------------------------
// 环境 profile：一次决定构建类型、签名要求、OSS 前缀、默认渠道状态与目标库
// ---------------------------------------------------------------------------
const ENV_ALIAS = {
  dev: 'dev',
  development: 'dev',
  local: 'dev',
  prod: 'prod',
  production: 'prod',
  online: 'prod',
};

const rawEnv = String(args.env || process.env.MIKA_RELEASE_ENV || '')
  .trim()
  .toLowerCase();
if (!rawEnv) {
  console.error(
    'ERR [失败] 必须显式指定环境：--env dev 或 --env prod（或设 MIKA_RELEASE_ENV）。\n' +
      '    不提供默认值是刻意的：漏指定会把开发包当正式版发出去，或把正式包登记到本地库。\n' +
      '    prod 先跑 --env prod --dry-run 看动作，确认后再加 --yes。'
  );
  process.exit(2);
}
const ENV_NAME = ENV_ALIAS[rawEnv];
if (!ENV_NAME) {
  fail(`未知环境 "${rawEnv}"。仅支持 --env dev | --env prod（别名 development/local/production 亦可）。`, 2);
}

const PROFILES = {
  dev: {
    label: '开发',
    defaultBase: 'http://127.0.0.1:3100',
    buildTask: ':app:assemblePerf -PsideInstall',
    buildType: 'perf',
    requireReleaseSigning: false,
    defaultChannel: 'beta',
    defaultStatus: 'draft',
    defaultOssPrefix: 'ai/ruyi/android/dev',
    requireConfirm: false,
  },
  prod: {
    label: '生产',
    defaultBase: null,
    buildTask: ':app:assembleRelease',
    buildType: 'release',
    requireReleaseSigning: true,
    defaultChannel: 'official',
    defaultStatus: 'published',
    defaultOssPrefix: 'ai/ruyi/android',
    requireConfirm: true,
  },
};
const profile = PROFILES[ENV_NAME];

// ---------------------------------------------------------------------------
// 配置解析
// ---------------------------------------------------------------------------
const ROOT = resolve(String(process.env.MIKA_ROOT || process.cwd()));
const BUILD_FILE = join(ROOT, 'app', 'build.gradle.kts');
if (!existsSync(BUILD_FILE)) {
  fail(`找不到 app/build.gradle.kts：${BUILD_FILE}。请在 Mika 项目根目录运行，或用 MIKA_ROOT 指定。`, 2);
}

// ---------------------------------------------------------------------------
// 账号文件与环境自动发现
// app-version-hub 的凭证落点是 ~/.config/apphub/agents/<账号>.env
// （每文件三行 export：APPHUB_BASE_URL / APPHUB_USERNAME / APPHUB_PASSWORD，权限 600）
// ---------------------------------------------------------------------------
const AGENT_DIR = join(process.env.HOME || '', '.config', 'apphub', 'agents');

function parseEnvFile(path) {
  const out = {};
  let raw = '';
  try {
    raw = readFileSync(path, 'utf8');
  } catch {
    return out;
  }
  for (const line of raw.split('\n')) {
    const m = line.match(/^\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$/);
    if (!m) continue;
    let value = m[2].trim();
    if (
      value.length >= 2 &&
      ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'")))
    ) {
      value = value.slice(1, -1);
    }
    out[m[1]] = value;
  }
  return out;
}

const ACCOUNT = String(args.account || process.env.MIKA_ACCOUNT || '').trim();
const accountEnv = (() => {
  if (!ACCOUNT) return {};
  const file = join(AGENT_DIR, `${ACCOUNT}.env`);
  if (!existsSync(file)) {
    const available = existsSync(AGENT_DIR)
      ? readdirSync(AGENT_DIR)
          .filter((f) => f.endsWith('.env'))
          .map((f) => f.replace(/\.env$/, ''))
          .join(', ')
      : '(目录不存在)';
    fail(`找不到账号文件：${file}\n    可用账号：${available}`, 2);
  }
  log('前置', `账号文件=${file}`);
  return parseEnvFile(file);
})();

// 账号文件 BASE_URL 相同即采用；不一致必须显式指定
function discoverAgentBaseUrl() {
  if (!existsSync(AGENT_DIR)) return '';
  const urls = new Set();
  for (const file of readdirSync(AGENT_DIR).filter((f) => f.endsWith('.env'))) {
    const env = parseEnvFile(join(AGENT_DIR, file));
    if (env.APPHUB_BASE_URL) urls.add(trimEndSlash(env.APPHUB_BASE_URL));
  }
  if (urls.size === 1) return [...urls][0];
  if (urls.size > 1) {
    fail(
      `账号文件里的 APPHUB_BASE_URL 不一致，请用 APPHUB_PROD_BASE_URL 显式指定：\n  ${[...urls].join('\n  ')}`,
      2
    );
  }
  return '';
}

function resolveBaseUrl() {
  if (ENV_NAME !== 'prod') {
    // dev 只认本地地址；账号文件里的 BASE_URL 是生产地址，绝不在这里借用
    const value = String(process.env.APPHUB_BASE_URL || '').trim();
    return trimEndSlash(value || profile.defaultBase);
  }
  const explicit = String(process.env.APPHUB_PROD_BASE_URL || '').trim();
  if (explicit) return trimEndSlash(explicit);
  if (accountEnv.APPHUB_BASE_URL) {
    warn(`生产地址取自账号文件（${ACCOUNT}）：${trimEndSlash(accountEnv.APPHUB_BASE_URL)}`);
    return trimEndSlash(accountEnv.APPHUB_BASE_URL);
  }
  const generic = String(process.env.APPHUB_BASE_URL || '').trim();
  if (generic) {
    warn('未提供 APPHUB_PROD_BASE_URL，回落到 APPHUB_BASE_URL。确认它指向生产（带 /apphub 前缀）。');
    return trimEndSlash(generic);
  }
  const discovered = discoverAgentBaseUrl();
  if (discovered) {
    warn(`未显式提供生产地址，采用 ${AGENT_DIR} 下的一致值：${discovered}`);
    return discovered;
  }
  fail(
    'prod 环境拿不到生产地址。三种给法任选其一：\n' +
      '    1) export APPHUB_PROD_BASE_URL=https://<PUBLIC_DOMAIN>/apphub\n' +
      '    2) --account <账号名>（读 ~/.config/apphub/agents/<账号名>.env）\n' +
      `    3) 在 ${AGENT_DIR} 下放至少一个含 APPHUB_BASE_URL 的 .env\n` +
      '    脚本不内置生产域名（仓库内所有部署文档均以占位符脱敏），缺失时拒绝执行，避免误发到本地库。',
    2
  );
}

function resolveCredential(suffix) {
  const scopedKey = ENV_NAME === 'prod' ? `APPHUB_PROD_${suffix}` : `APPHUB_${suffix}`;
  const scoped = String(process.env[scopedKey] || '').trim();
  if (scoped) return scoped;
  const fromAccount = String(accountEnv[`APPHUB_${suffix}`] || '').trim();
  if (fromAccount) return fromAccount;
  const genericKey = `APPHUB_${suffix}`;
  const generic = String(process.env[genericKey] || '').trim();
  if (generic && scopedKey !== genericKey) {
    warn(`未提供 ${scopedKey}，回落到 ${genericKey}。确认该账号在${profile.label}库中存在。`);
    return generic;
  }
  return '';
}

const APPHUB_BASE_URL = resolveBaseUrl();
const APPHUB_USERNAME = resolveCredential('USERNAME');
const APPHUB_PASSWORD = resolveCredential('PASSWORD');

const APP_NAME = String(process.env.APP_NAME || '如意 AI');
const CHANNEL = String(args.channel || process.env.MIKA_CHANNEL || profile.defaultChannel);
const STATUS = String(args.status || profile.defaultStatus);
const ABI = String(args.abi || process.env.MIKA_ABI || 'universal');
const CHANGELOG = String(args.changelog ?? process.env.MIKA_CHANGELOG ?? '');
const MIN_SUPPORTED = args['min-supported'] || process.env.MIKA_MIN_SUPPORTED || '0';

const OSS_BUCKET = String(process.env.OSS_BUCKET || 'chuangxin-prod');
const OSS_REGION = String(process.env.OSS_REGION || 'cn-hangzhou');
const OSS_ENDPOINT = String(process.env.OSS_ENDPOINT || 'https://oss-cn-hangzhou.aliyuncs.com');
const OSS_PREFIX = String(process.env.OSS_PREFIX || profile.defaultOssPrefix).replace(/^\/+|\/+$/g, '');
const OSS_PUBLIC_BASE = trimEndSlash(process.env.OSS_PUBLIC_BASE || 'https://oss-chuangxin.thchengtay.com');
const OSSUTIL = String(process.env.OSSUTIL || 'ossutil');
// 非默认位置的 ossutil 配置（CI / 换机器时用），对应 ossutil 的 --config-file
const OSS_CONFIG_FILE = String(process.env.OSS_CONFIG_FILE || '').trim();

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
// 签名校验（prod 必须有正式签名；调试签名装不上正式包）
// ---------------------------------------------------------------------------
function findApksigner() {
  const explicit = String(process.env.APKSIGNER || '').trim();
  if (explicit && existsSync(explicit)) return explicit;
  const sdk =
    process.env.ANDROID_HOME ||
    process.env.ANDROID_SDK_ROOT ||
    join(process.env.HOME || '', 'Library', 'Android', 'sdk');
  const dir = join(sdk, 'build-tools');
  if (!existsSync(dir)) return null;
  const versions = readdirSync(dir)
    .filter((v) => /^\d/.test(v))
    .sort((a, b) => b.localeCompare(a, undefined, { numeric: true }));
  for (const version of versions) {
    const candidate = join(dir, version, 'apksigner');
    if (existsSync(candidate)) return candidate;
  }
  return null;
}

function verifySigning(apkPath) {
  if (!profile.requireReleaseSigning) {
    log('校验', '签名=跳过（dev 环境允许调试签名，包可被覆盖安装）');
    return;
  }
  const signer = findApksigner();
  if (!signer) {
    fail(
      'prod 环境必须校验签名，但找不到 apksigner。请设置 APKSIGNER 或 ANDROID_HOME。\n' +
        '    原因：app/release-signing.properties 缺失时 Gradle 会静默回退调试签名，装不上正式包。',
      2
    );
  }
  const expectedCn = String(process.env.MIKA_EXPECTED_SIGNER_CN || 'Yuan AI');
  let out = '';
  try {
    out = execSync(`"${signer}" verify --print-certs "${apkPath}"`, { encoding: 'utf8' });
  } catch (error) {
    fail(`apksigner 校验失败（APK 可能未签名）：${error.message}`, 2);
  }
  if (!out.includes(`CN=${expectedCn}`)) {
    fail(
      `签名不符：期望 CN=${expectedCn}。这通常是 app/release-signing.properties 缺失导致的调试签名回退。\n` +
        `    实际输出：\n${out.trim()}`,
      2
    );
  }
  log('校验', `签名 OK（CN=${expectedCn}）`);
}

// ---------------------------------------------------------------------------
// prod 闸门：内网直链、显式确认
// ---------------------------------------------------------------------------
const PRIVATE_HOST_RE =
  /^https?:\/\/(?:localhost|127\.|0\.0\.0\.0|10\.|192\.168\.|172\.(?:1[6-9]|2\d|3[01])\.|\[::1\])/i;

function assertProdDownloadUrl(url) {
  if (ENV_NAME !== 'prod') return;
  if (PRIVATE_HOST_RE.test(url)) {
    fail(
      `prod 环境的下载直链不能是本机/内网地址：${url}\n` +
        '    客户端在公网访问不到它。请先上传到对外存储，或改用 dev 环境联调。',
      2
    );
  }
}

// ---------------------------------------------------------------------------
// OSS 凭证：在构建之前就判定，避免白等一次 gradle 构建
// ossutil 2.x 只认 OSS_ACCESS_KEY_ID / OSS_ACCESS_KEY_SECRET 或 ~/.ossutilconfig，
// 项目里常见的 ALIYUN_OSS_* / ALICLOUD_* / ALIBABA_CLOUD_* 都不生效。
// ---------------------------------------------------------------------------
function ossCredentialStatus() {
  const defaultConfig = join(process.env.HOME || '', '.ossutilconfig');
  const configPath = OSS_CONFIG_FILE || defaultConfig;
  if (existsSync(configPath)) return { ok: true, how: `配置文件 ${configPath}` };
  if (process.env.OSS_ACCESS_KEY_ID && process.env.OSS_ACCESS_KEY_SECRET) {
    return { ok: true, how: '环境变量 OSS_ACCESS_KEY_ID / OSS_ACCESS_KEY_SECRET' };
  }
  return { ok: false, how: `既无 ${configPath}，也无 OSS_ACCESS_KEY_ID / OSS_ACCESS_KEY_SECRET` };
}

function assertOssCredential() {
  if (URL_OVERRIDE) {
    log('前置', 'OSS 凭证=跳过（--url 已指定直链，不走上传）');
    return;
  }
  const status = ossCredentialStatus();
  if (!status.ok) {
    fail(
      `找不到 OSS 凭证：${status.how}\n` +
        '    配置方式（ossutil 2.x）：写入 ~/.ossutilconfig 的 [default] 段\n' +
        '      accessKeyID=...  accessKeySecret=...  region=cn-hangzhou  endpoint=https://oss-cn-hangzhou.aliyuncs.com\n' +
        '    或用 OSS_CONFIG_FILE 指向别的配置文件，或用 OSS_ACCESS_KEY_ID / OSS_ACCESS_KEY_SECRET 环境变量。',
      2
    );
  }
  log('前置', `OSS 凭证=${status.how}`);
}

// ---------------------------------------------------------------------------
// 主流程
// ---------------------------------------------------------------------------
const meta = readBuildMeta();
const apkFileName = `ruyi-${meta.versionName}-${ABI}.apk`;
const apkLocal = join(
  ROOT,
  'app',
  'build',
  'outputs',
  'apk',
  profile.buildType,
  `如意 AI-${meta.versionName}-${ABI}-${profile.buildType}.apk`
);
const objectKey = `${OSS_PREFIX}/${apkFileName}`;
// --url 给了就跳过 OSS 上传，直接用给定的直链登记（本地联调 / 包已在别处托管时用）
const URL_OVERRIDE = args.url ? String(args.url) : '';
const downloadUrl = URL_OVERRIDE || `${OSS_PUBLIC_BASE}/${objectKey}`;

console.log('==============================================');
console.log(` 环境：${ENV_NAME}（${profile.label}）  构建类型：${profile.buildType}`);
console.log(` 目标库：${APPHUB_BASE_URL}`);
console.log(` 登记：android/${CHANNEL}/${STATUS}  versionCode=${meta.versionCode}`);
console.log(` OSS：oss://${OSS_BUCKET}/${objectKey}${URL_OVERRIDE ? '（--url 已覆盖，跳过上传）' : ''}`);
console.log('==============================================');
log('信息', `appKey=${meta.appKey} 应用名=${APP_NAME}`);
log('信息', `versionName=${meta.versionName} versionCode=${meta.versionCode} ABI=${ABI}`);
log('信息', `APK 本地路径=${apkLocal}`);
log('信息', `下载直链=${downloadUrl}`);
log('信息', `账号=${APPHUB_USERNAME || '(未提供)'}`);

assertProdDownloadUrl(downloadUrl);

// 更新说明（changelog）会显示在客户端「关于页 → 更新说明」与公开门户 / 管理台；
// prod 对外发布必须写清本次改了什么，空说明禁止发生产，避免「发了没说明」。
if (ENV_NAME === 'prod' && !CHANGELOG.trim()) {
  fail(
    'prod 发版必须带更新说明：用 --changelog "本次改动…" 或 MIKA_CHANGELOG 提供（会显示在客户端关于页与公开门户）。',
    2
  );
}
if (ENV_NAME === 'dev' && !CHANGELOG.trim()) {
  warn('dev 未提供 --changelog，将登记为空说明（不影响 dev 联调，但 prod 会拒绝）。');
}

if (DRY_RUN) {
  if (!URL_OVERRIDE) {
    const status = ossCredentialStatus();
    console.log(`DRY OSS 凭证：${status.ok ? `OK（${status.how}）` : `缺失（${status.how}）`}`);
  }
  if (profile.requireConfirm) {
    console.log('\nDRY 提示：prod 实际发布会登记进生产库并对外生效，需追加 --yes 才会执行构建/上传/登记。');
  }
  console.log('DRY 以上为将要执行的动作，未做任何实际构建 / 上传 / 登记。');
  process.exit(0);
}

if (!APPHUB_USERNAME || !APPHUB_PASSWORD) {
  const keys = ENV_NAME === 'prod' ? 'APPHUB_PROD_USERNAME / APPHUB_PROD_PASSWORD' : 'APPHUB_USERNAME / APPHUB_PASSWORD';
  fail(`缺少 ${keys}（发布方账号口令），请用环境变量注入。prod 不会自动复用 dev 凭证以外的猜测值。`, 2);
}
if (!existsSync(APPHUB_CLI)) {
  fail(`找不到 apphub-publish.mjs：${APPHUB_CLI}。请用 APPHUB_CLI 指定。`, 2);
}
if (profile.requireConfirm && !CONFIRMED) {
  fail(
    'prod 发布会向生产库登记版本并对客户端生效，属对外变更。\n' +
      '    确认无误请追加 --yes 重跑；只想看动作请用 --env prod --dry-run。',
    2
  );
}

// 1) 构建（apphub 地址在此注入 APK，与登记目标同源）
assertOssCredential();
if (!SKIP_BUILD) {
  log('构建', profile.buildTask);
  sh(`${join(ROOT, 'gradlew')} ${profile.buildTask} -PapphubBaseUrl="${APPHUB_BASE_URL}"`, { cwd: ROOT });
} else {
  log('构建', `已跳过（--skip-build）。注意：APK 内嵌的检查更新地址由上次构建决定，请确认与 ${APPHUB_BASE_URL} 一致。`);
}
if (!existsSync(apkLocal)) {
  fail(
    `未找到产物 APK：${apkLocal}。构建是否成功？或用 --abi 指定其他架构（当前 ${ABI}）。\n` +
      `    注意产物目录按环境区分：dev 在 apk/perf/，prod 在 apk/release/。`,
    3
  );
}

// 2) 签名校验（prod 必须正式签名）
verifySigning(apkLocal);

// 3) 上传 OSS（给了 --url 则跳过）
if (URL_OVERRIDE) {
  log('上传', '已跳过 OSS 上传（使用 --url 指定的直链）');
} else {
  log('上传', `ossutil cp ${apkLocal} -> oss://${OSS_BUCKET}/${objectKey}`);
  const configFlag = OSS_CONFIG_FILE ? ` --config-file "${OSS_CONFIG_FILE}"` : '';
  sh(
    `${OSSUTIL} cp "${apkLocal}" "oss://${OSS_BUCKET}/${objectKey}" --region ${OSS_REGION} -e ${OSS_ENDPOINT}${configFlag} --force`,
    { cwd: ROOT }
  );
}

// 4) 确保应用已注册（幂等）
log('登记', 'apphub app:ensure');
sh(
  `node "${APPHUB_CLI}" app:ensure --key ${meta.appKey} --name "${APP_NAME}" --platforms android`,
  {
    cwd: ROOT,
    env: { ...process.env, APPHUB_BASE_URL, APPHUB_USERNAME, APPHUB_PASSWORD },
  }
);

// 5) 登记版本（--code 用 build.gradle 的 versionCode，单一事实源；--file 算 sha256/size）
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

// 6) 结果汇报
console.log('\n===== 发布完成 =====');
console.log(`环境：${ENV_NAME}（${profile.label}）  目标库：${APPHUB_BASE_URL}`);
console.log(`应用：${APP_NAME}（${meta.appKey}）`);
console.log(`版本：v${meta.versionName}（code=${meta.versionCode}）android/${CHANNEL}/${STATUS}`);
console.log(`下载：${downloadUrl}`);
console.log(
  `检查更新：${APPHUB_BASE_URL}/api/v1/update/check?appKey=${meta.appKey}&platform=android&channel=${CHANNEL}`
);
if (ENV_NAME === 'dev') {
  console.log('提示：dev 包已用 .perf 包名并排安装，正式包与用户数据不受影响。');
}
