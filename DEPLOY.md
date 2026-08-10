# Backend 배포 가이드

운영 배포는 **latest 태그를 쓰지 않고**, 로컬에서 태그가 붙은 Docker 이미지를
빌드한 뒤 Docker Hub에 push하고 EC2에서 pull/run 합니다.

## 기본 정보

- Docker Hub image: `youngjin179/ktb-backend:<TAG>`
- MongoDB: `10.0.2.127:27017`
- Redis: `10.0.2.157:6379`
- Backend EC2: `10.0.2.238`
- ALB: `http://public-ktb-alb-974381789.ap-northeast-2.elb.amazonaws.com`
- EC2 env: `/etc/ktb/backend-app.env`
- EC2 compose: `/home/ubuntu/ktb-chat-backend/docker-compose.prod.yaml`

## 1. 태그 설정

Backend repo root에서 실행합니다.

```bash
export DOCKER_NS=youngjin179
export TAG=$(git rev-parse --short HEAD)-smoke1
```

예:

```text
youngjin179/ktb-backend:abc1234-smoke1
```

## 2. Docker Hub 로그인

```bash
docker login
```

## 3. 이미지 빌드/푸시

```bash
docker build \
  -t $DOCKER_NS/ktb-backend:$TAG \
  .

docker push $DOCKER_NS/ktb-backend:$TAG
```

## 4. EC2 env 확인

Backend EC2에서 `/etc/ktb/backend-app.env`를 관리합니다. 로컬 `.env`는 운영에
쓰지 않습니다.

필수 ALB origin:

```env
CORS_ALLOWED_ORIGINS=http://public-ktb-alb-974381789.ap-northeast-2.elb.amazonaws.com
SOCKETIO_SERVER_ORIGIN=http://public-ktb-alb-974381789.ap-northeast-2.elb.amazonaws.com
```

확인:

```bash
sudo awk -F= '/^(CORS_ALLOWED_ORIGINS|SOCKETIO_SERVER_ORIGIN)=/ {print $1}' /etc/ktb/backend-app.env
```

## 5. compose 파일 배포

```bash
make deploy-compose DEPLOY_SERVERS=ktb-backend
```

`make deploy-compose`는 아래 명령의 wrapper입니다.

```bash
rsync -az docker-compose.prod.yaml ktb-backend:/home/ubuntu/ktb-chat-backend/
```

## 6. EC2에서 새 이미지 실행

```bash
BACKEND_IMAGE=$DOCKER_NS/ktb-backend:$TAG \
make compose-up-servers DEPLOY_SERVERS=ktb-backend
```

직접 EC2에서 실행하려면:

```bash
export TAG=<실제_TAG>
sudo docker pull youngjin179/ktb-backend:$TAG

cd /home/ubuntu/ktb-chat-backend
sudo env BACKEND_IMAGE=youngjin179/ktb-backend:$TAG \
  docker-compose -f docker-compose.prod.yaml up -d
```

`docker-compose.prod.yaml`은 기본적으로 다음 포트를 publish합니다.

```text
5001 -> 5001
5002 -> 5002
```

Prometheus가 backend HTTP 포트 `5001`을 보므로 `5001:5001`을 기준으로 둡니다.

## 7. 확인

```bash
sudo docker ps --filter name=backend-app
curl http://localhost:5001/actuator/health
sudo docker logs -f backend-app
```

## make는 왜 쓰나?

`make`는 Docker를 대체하지 않습니다. SSH/rsync/docker-compose 명령을 짧게
부르는 wrapper입니다.

- 이미지 생성: `docker build`
- 이미지 업로드: `docker push`
- EC2 실행: `docker-compose up -d`
- 반복 명령 단축: `make deploy-compose`, `make compose-up-servers`
