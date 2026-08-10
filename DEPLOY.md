# Backend 배포 가이드

이 repo는 백엔드 전용 repo입니다. 운영 실행 기준은 Docker 이미지와
`docker-compose.prod.yaml`입니다.

## 구조

- 로컬 개발 env: `.env`
- 운영 env 예시: `backend-app.env.example`
- EC2 운영 env: `/etc/ktb/backend-app.env`
- EC2 compose: `/home/ubuntu/ktb-chat-backend/docker-compose.prod.yaml`
- 운영 이미지: `youngjin179/ktb-backend:1.0.0`

운영에서는 로컬 `.env`를 쓰지 않습니다.

## 1. Docker 이미지 빌드

```bash
cd apps/backend
docker build -t youngjin179/ktb-backend:1.0.0 .
```

## 2. Docker 이미지 push

```bash
docker push youngjin179/ktb-backend:1.0.0
```

## 3. EC2 운영 env 준비

Backend EC2에서 최초 1회만 설정합니다.

```bash
sudo mkdir -p /etc/ktb
sudo nano /etc/ktb/backend-app.env
sudo chown root:root /etc/ktb/backend-app.env
sudo chmod 600 /etc/ktb/backend-app.env
```

필수 키는 `backend-app.env.example`을 기준으로 채웁니다.

## 4. compose 파일 배포

로컬에서 실행합니다.

```bash
cd apps/backend
make deploy-compose DEPLOY_SERVERS=ktb-backend
```

`make deploy-compose`는 아래 명령을 짧게 감싼 것입니다.

```bash
rsync -az docker-compose.prod.yaml ktb-backend:/home/ubuntu/ktb-chat-backend/
```

## 5. EC2에서 컨테이너 재실행

로컬에서 실행합니다.

```bash
cd apps/backend
make compose-up-servers DEPLOY_SERVERS=ktb-backend
```

`make compose-up-servers`는 EC2에서 아래 명령을 실행하는 wrapper입니다.

```bash
cd /home/ubuntu/ktb-chat-backend
sudo docker-compose -f docker-compose.prod.yaml up -d
```

직접 EC2에 접속해서 실행해도 됩니다.

## 6. 확인

Backend EC2에서 확인합니다.

```bash
sudo docker ps --filter name=backend-app
curl http://localhost:8080/api/health
python3 - <<'PY'
import urllib.request
url = "http://localhost:5002/socket.io/?EIO=4&transport=polling"
with urllib.request.urlopen(url, timeout=5) as r:
    print(r.status)
    print(r.read(160))
PY
```

정상 포트:

```text
0.0.0.0:8080->5001/tcp
0.0.0.0:5002->5002/tcp
```

## 왜 make를 쓰나?

`make`는 필수가 아닙니다. 긴 SSH/rsync/docker-compose 명령을 짧게 부르는
wrapper입니다.

- 이미지를 새로 만들 때: `docker build`, `docker push`
- 서버에서 실행할 때: `docker-compose up -d`
- 반복 명령을 줄이고 싶을 때: `make deploy-compose`, `make compose-up-servers`
