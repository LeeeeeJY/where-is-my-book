# VM 자동 배포

새 코드가 올라오면 서버가 알아서 받아서 다시 뜨게 합니다. 지금은 손으로 네 줄을 치고
10분을 기다려야 하는데, 그 기다림을 사람이 할 이유가 없습니다.

**GitHub 에 비밀키를 맡기지 않고, 바깥에서 VM 으로 들어오는 길도 열지 않습니다.** VM 이
주기적으로 저장소를 들여다보는 방식이라 나가는 방향만 씁니다.

## 켜기

서버에서 한 번만 하면 됩니다. **sudo 가 필요 없습니다.**

```bash
mkdir -p ~/.config/systemd/user
cp ~/where-is-my-book/scripts/vm/wimb-deploy.{service,timer} ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now wimb-deploy.timer
```

그리고 **로그아웃해도 계속 돌게** 해 둡니다. 이걸 빠뜨리면 SSH 창을 닫는 순간 멈춥니다.

```bash
loginctl enable-linger "$USER"
```

## 확인

```bash
systemctl --user list-timers wimb-deploy.timer
```

```bash
journalctl --user -u wimb-deploy.service -n 40 --no-pager
```

새 코드가 없으면 `새 코드가 없습니다` 한 줄만 남기고 끝납니다. 있으면 받아서 빌드하고,
**뜬 것을 확인한 뒤에** 끝납니다. 「배포했다」와 「실제로 뜬다」는 다르기 때문입니다.

## 지금 바로 한 번 돌려 보기

```bash
~/where-is-my-book/scripts/vm/deploy.sh
```

## 알아 둘 것

- **빌드가 실패하면 돌던 서버를 건드리지 않습니다.** 먼저 이미지를 만들고 성공했을 때만
  컨테이너를 바꿉니다. 반대로 하면 빌드가 깨진 날 사이트가 통째로 내려갑니다.
- **5분마다 확인하지만 저장소만 봅니다.** 새 코드가 없으면 `git fetch` 한 번으로 끝나서
  거의 아무 일도 하지 않습니다.
- 빌드가 10분쯤 걸리는데 타이머는 5분마다 깨웁니다. systemd 가 같은 서비스를 겹쳐 돌리지
  않으므로 문제가 되지 않습니다.
- 배포가 끝나면 오래된 이미지를 정리합니다. **30GB 디스크가 금방 찹니다.**
- 키는 `~/wimb.env` 에서 읽습니다. 이 파일이 없으면 배포가 실패합니다.

## 다른 브랜치를 보게 하려면

```bash
systemctl --user edit wimb-deploy.service
```

```
[Service]
Environment=WIMB_BRANCH=main
```

## 더 빠르게 하고 싶으면

빌드를 **GitHub Actions 에서 하고 VM 은 받기만** 하는 방법이 있습니다. e2-micro 에서 10분
걸리는 빌드가 러너에서는 1~2분이고, VM 은 `docker pull` 만 하면 되니 배포가 1분 안에
끝납니다. 컨테이너 레지스트리(ghcr.io)를 하나 붙여야 해서 지금은 하지 않았습니다.

**정보나루 호출을 Actions 에서 하는 것과는 다른 이야기입니다.** 그건 러너 IP 가 고정되지
않아 한도가 500건으로 떨어지기 때문에 금지한 것이고, 이미지를 만드는 일은 정보나루를
부르지 않습니다.
