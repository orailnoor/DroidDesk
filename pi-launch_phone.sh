#!/bin/bash

# ========================================================
# RPI DISPLAY BRIDGE: AUTO-CONNECT SCRIPT
# This script detects the phone via USB and launches VNC
# ========================================================

echo "Starting Phone Display Bridge..."

# 1. Loop until the phone is detected (max 30 seconds)
MAX_RETRIES=30
RETRY_COUNT=0
PHONE_IP=""

while [ $RETRY_COUNT -lt $MAX_RETRIES ]; do
    # Find the Gateway IP from the routing table
    PHONE_IP=$(ip route | grep default | awk '{print $3}' | head -n 1)
    
    if [ -n "$PHONE_IP" ]; then
        echo "[+] Phone detected at IP: $PHONE_IP"
        break
    else
        echo "[-] Waiting for USB Tethering... ($((MAX_RETRIES - RETRY_COUNT))s)"
        sleep 1
        RETRY_COUNT=$((RETRY_COUNT + 1))
    fi
done

# 2. Final check and Launch
if [ -n "$PHONE_IP" ]; then
    echo "[+] Launching VNC Desktop in FullScreen..."
    
    # Optimization Flags for Pi Zero W Performance:
    # -FullScreen: No window borders
    # -QualityLevel 4: Balance between speed and looks
    # -CompressLevel 9: High compression to save Pi CPU
    # -LowColorLevel 1: Use 16-bit color for 2x faster rendering
    
    vncviewer $PHONE_IP::5901 \
        -FullScreen \
        -QualityLevel 4 \
        -CompressLevel 9 \
        -LowColorLevel 1 \
        -MenuKey=F8
else
    echo "[!] ERROR: No phone detected via USB."
    echo "[!] Make sure 'USB Tethering' is ON in your phone settings."
    sleep 5
fi
