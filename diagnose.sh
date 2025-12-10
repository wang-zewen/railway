#!/bin/bash

echo "====== Xray 诊断工具 ======"
echo ""

# 检查端口监听
echo "1. 检查端口监听状态:"
echo "   - 主端口 (8001):"
netstat -tlnp 2>/dev/null | grep :8001 || ss -tlnp 2>/dev/null | grep :8001 || echo "   端口 8001 未监听"

echo "   - WebSocket 端口 (3002):"
netstat -tlnp 2>/dev/null | grep :3002 || ss -tlnp 2>/dev/null | grep :3002 || echo "   端口 3002 未监听"

echo ""

# 检查进程
echo "2. 检查 Xray/Web 进程:"
ps aux | grep -E "(xray|web)" | grep -v grep || echo "   未找到 xray 进程"

echo ""

# 检查配置文件
echo "3. 检查配置文件:"
if [ -f "./tmp/config.json" ]; then
    echo "   ✓ config.json 存在"
    echo "   检查 vless-argo 路径配置:"
    grep -o '"path":"[^"]*"' ./tmp/config.json | head -5
else
    echo "   ✗ config.json 不存在"
fi

echo ""

# 测试本地连接
echo "4. 测试本地端口连接:"
timeout 2 bash -c "cat < /dev/null > /dev/tcp/127.0.0.1/8001" 2>/dev/null && echo "   ✓ 端口 8001 可连接" || echo "   ✗ 端口 8001 无法连接"
timeout 2 bash -c "cat < /dev/null > /dev/tcp/127.0.0.1/3002" 2>/dev/null && echo "   ✓ 端口 3002 可连接" || echo "   ✗ 端口 3002 无法连接"

echo ""
echo "====== 诊断完成 ======"
