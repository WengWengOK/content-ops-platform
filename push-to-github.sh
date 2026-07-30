#!/bin/bash
# 推送 Content Ops Agent Platform 到 GitHub
# 用法：在你的终端中运行此脚本
#   chmod +x push-to-github.sh
#   ./push-to-github.sh

set -e

REPO_URL="https://github.com/WengWengOK/content-ops-platform.git"
PROJECT_DIR="/workspace/content-ops-agent-platform-monolithic"

cd "$PROJECT_DIR"

# 检查是否有 remote
if git remote get-url origin &>/dev/null; then
    git remote set-url origin "$REPO_URL"
else
    git remote add origin "$REPO_URL"
fi

echo "========================================"
echo "  Content Ops Agent Platform → GitHub"
echo "========================================"
echo ""
echo "远程仓库: $REPO_URL"
echo "本地分支: $(git branch --show-current)"
echo "提交记录: $(git rev-list --count HEAD) commits"
echo "文件数量: $(git ls-files | wc -l) files"
echo ""
echo "正在推送..."
echo ""

git push -u origin main

if [ $? -eq 0 ]; then
    echo ""
    echo "✅ 推送成功！"
    echo "仓库地址: https://github.com/WengWengOK/content-ops-platform"
else
    echo ""
    echo "❌ 推送失败，可能需要认证。请运行："
    echo "  gh auth login"
    echo "  或设置 Git credentials"
    echo "  或使用 token: git remote set-url origin https://<TOKEN>@github.com/WengWengOK/content-ops-platform.git"
fi
