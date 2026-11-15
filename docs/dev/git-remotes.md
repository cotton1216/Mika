# 三远程协作：origin、me 与 xxt

本仓库的工作副本同时挂了三个 Git 远程：原作者仓库、自己的 fork、公司内网仓库。
三条线血统不同、写权限不同，误用会丢代码或误推。本文记录它们的映射关系、
当前同步状态和日常操作规范。

## 远程映射

| remote | URL | 角色 | 写权限 |
| --- | --- | --- | --- |
| `origin` | `https://github.com/wangzan101/Mika` | 原作者上游，真正的源仓库 | 只读。不属于 collaborator，push 会被拒 |
| `me` | `git@github.com:cotton1216/Mika.git` | 自己的 fork，走 SSH | 可写，日常提交推这里 |
| `xxt` | `ssh://git@10.10.117.12:2222/xxt/ai-server/ruyuan-app.git` | 公司内网 GitLab | 可写，内网发布走这里 |

三个远程都使用标准 fetch refspec，即 `+refs/heads/*:refs/remotes/<remote>/*`，
所以 `git fetch --all` 会把每个远程的分支分别落到 `refs/remotes/origin/`、
`refs/remotes/me/`、`refs/remotes/xxt/` 下，彼此不会覆盖。

查看当前配置：

```sh
git remote -v
git config --get-all remote.origin.fetch
```

## 快照：2026-11-15 的同步状态

写文档时的实测数据，仅作参照，后续会变：

| 引用 | commit | 说明 |
| --- | --- | --- |
| `origin/main` | `4387bfe` | 与 `me/main` 同一个提交，fork 没有偏离上游 |
| `me/main` | `4387bfe` | 与上游一致 |
| 本地 `main` | `c8178d21` | 落后 `origin/main` 259 个提交，没有本地独有提交 |
| `xxt/main` | `c8178d21` | 与本地 `main` 一致 |
| `me/me` | `b075159c` | 当前工作分支 `ruyuan` 的上游，两边完全同步 |

几个判断方法：

```sh
# 左数为对方独有提交数，右数为本地独有提交数
git rev-list --left-right --count origin/main...main

# 看落后了哪些提交
git log --oneline main..origin/main

# 找共同祖先，确认是分叉还是纯落后
git merge-base origin/main main
```

本地 `main` 的 `merge-base` 就是它自己的 HEAD，说明它只是 `origin/main` 的祖先，
属于纯落后，可以安全快进，不需要 rebase 或解决冲突。

## 操作规范

### 拉取

先拉全部远程再决定怎么合并，避免只拉了某一条线就动手：

```sh
git fetch --all --prune
```

从上游同步主分支，纯落后时用 `--ff-only` 把「意外分叉」变成硬失败，而不是悄悄生成合并提交：

```sh
git switch main
git merge --ff-only origin/main
```

如有本地未提交改动，先 `git stash`，或者在新分支上 `git pull --rebase origin main`。

### 推送

永远显式写出远程名，不依赖默认值：

```sh
git push me main          # 推到自己的 fork
git push me ruyuan        # 当前分支推到 fork 的同名分支
git push xxt main         # 推到内网 GitLab
```

第一次推新分支顺手建立上游关系：

```sh
git push -u me ruyuan
```

### 建议的本地配置

这两条能显著降低误推概率，属于本地配置，只影响这台机器：

```sh
git config remote.pushDefault me    # 裸敲 git push 默认打到 fork
git config push.default current    # 无上游时按同名分支推，而不是报错或推错
```

配完之后，裸 `git push` 一定落在 `me`；推内网必须显式
`git push xxt ...`，不会因为忘记写远程名而推到无权限的 `origin`。

### 绝对不能做的事

**不要对本仓库的任何非空远程执行 `git push --mirror`。**

`--mirror` 会把对方仓库强制对齐成本地快照，本地不存在的分支和 tag 会被删除。
三条线各有关键分支，后果实打实：

- 对 `xxt` 做 mirror：`merge/ruyi-upstream`、`storage-opt`、`ruyi-login`、
  `ruyi-remote`、`ui/polish` 这些内网分支会被清掉
- 对 `me` 做 mirror：fork 上独有的 `me` 分支会被删掉
- 对 `origin` 做 mirror：无写权限，直接失败

`--mirror` 只允许指向**全空的**新建仓库，例如要把快照搬到新私有仓库时，
先建空仓库再 mirror 推送。

### 分支与工作树的约定

- `main` 跟随 `origin/main`，只用于跟上游保持同步，不在上面直接开发
- 日常开发在功能分支上，推 `me`
- 涉及内网联调、`ruyi-*` 相关的改动推 `xxt`
- 多分支并行时用 `git worktree` 隔离，例如 `wt-apphub-update` 工作树承载
  `feature/apphub-update`，不要在多分支之间反复 stash
- 保存现场用的分支（`backup-prelogin`、`saved-merge-branding`、`saved-ui-css`）
  不设上游，只做本地备份，不要推到任何远程

## 常见问题

**为什么 `git push` 会失败并提示权限不足。**
默认远程不是 `origin` 就是没配 `remote.pushDefault`，推到了原作者仓库。核对
`git remote -v` 后改成 `git push me <branch>`，或按上文配置 `push.default`。

**为什么 `main` 显示落后几百个提交，但代码看起来没差那么多。**
上游在这段时间持续有提交，本地 `main` 从 `2026-11-11` 之后没有再 `fetch` 合并。
按「拉取」一节做一次快进即可，不需要手工挑选提交。

**fork 和上游应该是同一个提交，为什么还要保留 `me`。**
`origin` 没有写权限，所有需要 push 的分支都必须落在 `me`；`me/main` 与上游一致
是「fork 没有偏离」的证据，也是提 PR 的基线。
