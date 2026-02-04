#!/bin/bash
set -e

echo "=========================================="
echo "  MT Party Game - Oracle Cloud VM Setup"
echo "=========================================="

# ---- 1. Swap 4GB 설정 ----
echo ""
echo "[1/5] Swap 4GB 설정..."
if [ -f /swapfile ]; then
    echo "  Swap이 이미 존재합니다. 건너뜁니다."
else
    sudo fallocate -l 4G /swapfile
    sudo chmod 600 /swapfile
    sudo mkswap /swapfile
    sudo swapon /swapfile
    echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
    # Swap 사용 빈도 조절 (메모리 부족할 때만 swap 사용)
    echo 'vm.swappiness=10' | sudo tee -a /etc/sysctl.conf
    sudo sysctl -p
    echo "  Swap 4GB 설정 완료"
fi
free -h

# ---- 2. Docker 설치 ----
echo ""
echo "[2/5] Docker 설치..."
if command -v docker &> /dev/null; then
    echo "  Docker가 이미 설치되어 있습니다."
else
    sudo apt update && sudo apt install -y docker.io docker-compose-plugin
    sudo systemctl enable docker
    sudo systemctl start docker
    sudo usermod -aG docker $USER
    echo "  Docker 설치 완료"
fi

# ---- 3. 방화벽 설정 (포트 80) ----
echo ""
echo "[3/5] 방화벽 포트 80 열기..."
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 80 -j ACCEPT
if command -v netfilter-persistent &> /dev/null; then
    sudo netfilter-persistent save
else
    sudo apt install -y iptables-persistent
    sudo netfilter-persistent save
fi
echo "  방화벽 설정 완료"

# ---- 4. 프로젝트 클론 ----
echo ""
echo "[4/5] 프로젝트 클론..."
mkdir -p ~/projects && cd ~/projects

if [ -d "mt-party-game" ]; then
    echo "  mt-party-game 이미 존재 - pull..."
    cd mt-party-game && git pull && cd ..
else
    git clone https://github.com/Osssai-52/mt-party-game.git
fi

if [ -d "recreation" ]; then
    echo "  recreation 이미 존재 - pull..."
    cd recreation && git pull && cd ..
else
    git clone https://github.com/ujinchoi03-star/recreation.git
fi

# ---- 5. 환경변수 설정 ----
echo ""
echo "[5/5] 환경변수 설정..."
cd ~/projects/recreation

# VM의 공인 IP 자동 감지
PUBLIC_IP=$(curl -s ifconfig.me)
echo "  감지된 공인 IP: $PUBLIC_IP"

cat > .env << EOF
MYSQL_ROOT_PASSWORD=mtgameroot
MYSQL_DATABASE=mt_game
PUBLIC_IP=$PUBLIC_IP
EOF

echo "  .env 파일 생성 완료"

# ---- 완료 ----
echo ""
echo "=========================================="
echo "  설정 완료!"
echo "=========================================="
echo ""
echo "다음 명령어로 서비스를 시작하세요:"
echo ""
echo "  cd ~/projects/recreation"
echo "  docker compose up -d --build"
echo ""
echo "빌드 완료 후 접속:"
echo "  http://$PUBLIC_IP"
echo ""
echo "※ 첫 빌드는 느릴 수 있습니다 (Swap 사용)"
echo "※ 로그 확인: docker compose logs -f"
echo "=========================================="
