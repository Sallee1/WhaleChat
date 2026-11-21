#!/usr/bin/env bash
# 发布一版：打 tag → 推送 → 由 GitHub Actions 构建、签名、生成 changelog、上传 Release
#
# 用法：
#   tools/release.sh 1.0.0          # 用当前 HEAD 打 v1.0.0 并推送
#
# 发布说明（release notes）由 .github/workflows/release.yml 自动生成：
#   - 默认从上一个 v* tag 以来的提交（Conventional Commits）生成 changelog，
#     并调用 DeepSeek API 翻译成中文，需要 secret DEEPSEEK_API_KEY；
#   - 如果仓库里存在 .release-notes-<版本号>.md，则直接使用该手写文件。
#
# 前置：
#   1. 仓库 secrets 已配置 RELEASE_KEYSTORE_BASE64 / RELEASE_STORE_PASSWORD /
#      RELEASE_KEY_ALIAS / RELEASE_KEY_PASSWORD / DEEPSEEK_API_KEY
#   2. 本地工作区干净，且已与 origin/main 同步
set -euo pipefail

VERSION="${1:-}"
[ -n "$VERSION" ] || { echo "用法: tools/release.sh <版本号，如 1.0.0>" >&2; exit 1; }

TAG="v$VERSION"
cd "$(git rev-parse --show-toplevel)"

# --- 前置检查 ---------------------------------------------------------------
[ -z "$(git status --porcelain)" ] || { echo "工作区不干净，先提交或暂存改动" >&2; exit 1; }
git rev-parse -q --verify "refs/tags/$TAG" >/dev/null && { echo "tag $TAG 已存在" >&2; exit 1; }

BRANCH="$(git rev-parse --abbrev-ref HEAD)"
git fetch origin "$BRANCH" --quiet
[ "$(git rev-parse HEAD)" = "$(git rev-parse "origin/$BRANCH")" ] || {
  echo "HEAD 与 origin/$BRANCH 不一致，先 push 或 rebase" >&2; exit 1; }

# --- 本地先构建一次，避免 CI 才发现编译错误 ---------------------------------
export JAVA_HOME="${JAVA_HOME:-/c/Program Files/Eclipse Adoptium/jdk-21.0.13.9-hotspot}"
./gradlew assembleRelease --console=plain -q

# --- 打 tag 并推送（push tag 即触发 release.yml） ----------------------------
git tag -a "$TAG" -m "WhaleChat $VERSION"
git push origin "$TAG"

echo "已推送 $TAG，GitHub Actions 正在构建并发布："
echo "  https://github.com/Sallee1/WhaleChat/actions"
echo "  https://github.com/Sallee1/WhaleChat/releases/tag/$TAG"
