#!/bin/bash

echo "====== 配置检查工具 ======"
echo ""

CONFIG_FILE="./tmp/config.json"

if [ ! -f "$CONFIG_FILE" ]; then
    echo "❌ 配置文件不存在: $CONFIG_FILE"
    exit 1
fi

echo "✓ 配置文件存在"
echo ""

# 检查路径配置
echo "1. 检查 WebSocket 路径配置:"
echo "   Fallback 配置:"
jq -r '.inbounds[0].settings.fallbacks[] | select(.path) | "     - \(.path) → port \(.dest)"' "$CONFIG_FILE" 2>/dev/null || echo "   无法解析 JSON"

echo ""
echo "   WebSocket Inbound 路径:"
jq -r '.inbounds[] | select(.protocol=="vless" and .streamSettings.network=="ws") | "     - VLESS WS: \(.streamSettings.wsSettings.path) (port \(.port))"' "$CONFIG_FILE" 2>/dev/null
jq -r '.inbounds[] | select(.protocol=="vmess") | "     - VMess WS: \(.streamSettings.wsSettings.path) (port \(.port))"' "$CONFIG_FILE" 2>/dev/null
jq -r '.inbounds[] | select(.protocol=="trojan") | "     - Trojan WS: \(.streamSettings.wsSettings.path) (port \(.port))"' "$CONFIG_FILE" 2>/dev/null

echo ""

# 检查端口配置
echo "2. 检查端口配置:"
echo "   主端口 (ARGO_PORT):"
jq -r '.inbounds[0].port' "$CONFIG_FILE" 2>/dev/null | xargs -I {} echo "     - {}"

echo ""

# 检查 UUID
echo "3. 检查 UUID 配置:"
jq -r '.inbounds[0].settings.clients[0].id' "$CONFIG_FILE" 2>/dev/null | xargs -I {} echo "     - {}"

echo ""

# 验证路径一致性
echo "4. 验证路径一致性:"
VLESS_FALLBACK=$(jq -r '.inbounds[0].settings.fallbacks[] | select(.path | contains("vless")) | .path' "$CONFIG_FILE" 2>/dev/null)
VLESS_WS_PATH=$(jq -r '.inbounds[] | select(.protocol=="vless" and .streamSettings.network=="ws") | .streamSettings.wsSettings.path' "$CONFIG_FILE" 2>/dev/null)

if [ "$VLESS_FALLBACK" == "$VLESS_WS_PATH" ]; then
    echo "   ✓ VLESS 路径一致: $VLESS_FALLBACK"
else
    echo "   ❌ VLESS 路径不一致!"
    echo "      Fallback: $VLESS_FALLBACK"
    echo "      WebSocket: $VLESS_WS_PATH"
fi

echo ""
echo "====== 检查完成 ======"
