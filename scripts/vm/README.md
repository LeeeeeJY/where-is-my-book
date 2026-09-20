# VM 자동 배포

푸시하면 사이트에 반영되기까지 사람이 할 일이 없습니다.

```
푸시 → GitHub Actions 가 테스트를 돌리고 이미지를 만들어 올림      2~3분
           ↓
      VM 타이머가 2분마다 새 이미지가 있는지 물어봄
           ↓
      있으면 받아서 컨테이너를 갈아 끼움                          1분 안쪽
```

**이미지를 VM 에서 만들지 않는 것이 핵심입니다.** 무료 등급 e2-micro 는 기본 CPU 가 코어의
0.25개이고 표준 영구 디스크가 30GB 에서 쓰기 45 IOPS 라, 자바를 컴파일하면 10~15분이
걸렸습니다. 파일 하나 복사하는 데 26초가 걸린 적도 있습니다. 러너에서는 같은 일이 2~3분이고,
VM 은 다 만들어진 것을 받기만 합니다.

**GitHub 에 비밀키를 맡기지 않고, 바깥에서 VM 으로 들어오는 길도 열지 않습니다.** VM 이
주기적으로 레지스트리를 들여다보는 방식이라 나가는 방향만 씁니다. 정보나루 인증키는
VM 의 `~/wimb.env` 에만 있고 이미지에도 저장소에도 들어가지 않습니다.

**여기서 정보나루를 부르지 않습니다.** 러너 IP 는 고정되지 않아 한도가 조용히 500건으로
떨어지는데(`CLAUDE.md`), 이미지를 만드는 일은 정보나루를 부르지 않으므로 무관합니다.

## 켜기

서버에서 한 번만 하면 됩니다. **sudo 가 필요 없습니다.**

```bash
cd ~/where-is-my-book*          # clone 한 디렉터리 이름이 무엇이든 들어갑니다
mkdir -p ~/.config/systemd/user
sed "s|^ExecStart=.*|ExecStart=$PWD/scripts/vm/deploy.sh|" \
    scripts/vm/wimb-deploy.service > ~/.config/systemd/user/wimb-deploy.service
cp scripts/vm/wimb-deploy.timer ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now wimb-deploy.timer
```

`sed` 로 경로를 박아 넣는 이유는, GitHub 에서 저장소 이름이 바뀌어 clone 한 디렉터리가
`where-is-my-book` 일 수도 `where-is-my-books` 일 수도 있기 때문입니다. 유닛 파일에
한쪽으로 적어 두면 다른 쪽에서는 타이머가 조용히 실패만 합니다.

그리고 **로그아웃해도 계속 돌게** 해 둡니다. 이걸 빠뜨리면 SSH 창을 닫는 순간 멈춥니다.

```bash
loginctl enable-linger "$USER"
```

## 이미지를 받을 수 있어야 합니다

**저장소가 공개면 아무 설정도 필요 없습니다.** 이미지도 함께 공개로 올라가서 VM 이
인증 없이 받아 갑니다.

저장소를 비공개로 두려면 둘 중 하나입니다.

- **이미지만 공개로 바꿉니다.** GitHub 저장소 화면 오른쪽 Packages 에서 그 이미지를 열고
  Package settings → Change visibility → Public. 저장소는 비공개 그대로입니다.
- **비공개로 유지하고 VM 에 로그인시킵니다.** 이 경우 무료 한도가 저장 500MB, 월 전송
  1GB 라 배포를 열댓 번 하면 찹니다.

## 확인

```bash
systemctl --user list-timers wimb-deploy.timer
journalctl --user -u wimb-deploy.service -n 40 --no-pager
```

새 이미지가 없으면 `새 이미지가 없습니다` 한 줄만 남기고 끝납니다. 있으면 받아서 바꾸고,
**뜬 것을 확인한 뒤에** 끝납니다. 「배포했다」와 「실제로 뜬다」는 다르기 때문입니다.

흘러가는 것을 지켜보려면 `-f` 를 붙입니다. `Ctrl+C` 로 빠져나와도 배포는 계속됩니다.

```bash
journalctl --user -u wimb-deploy.service -f
```

## 지금 바로 한 번 돌려 보기

```bash
~/where-is-my-book*/scripts/vm/deploy.sh
```

## 설정 바꾸기

환경 변수는 `~/wimb.env` 에 있습니다. **이 파일을 손으로 고치지 마세요.**

```bash
~/where-is-my-book*/scripts/vm/set-env.sh WIMB_CORS_ALLOWED_ORIGINS='https://a.vercel.app,https://a-*.vercel.app'
~/where-is-my-book*/scripts/vm/set-env.sh --show      # 값은 빼고 키 이름만 봅니다
```

**고치는 것과 반영되는 것이 다르고, 그 차이가 조용합니다.** 환경 변수는 컨테이너가 뜰 때
한 번 읽으므로 파일을 고쳐도 돌고 있는 서버는 예전 값 그대로입니다. 그 상태로
`deploy.sh` 를 불러도 이미지가 그대로라 「새 이미지가 없습니다」 한 줄만 남고 끝납니다.
**두 단계 모두 오류를 내지 않아서 고친 사람은 반영된 줄 압니다.** 바뀐 것이 CORS 허용
주소처럼 브라우저에서만 드러나는 값이면 멀쩡한 코드를 뜯어보게 됩니다. `set-env.sh` 는
고치는 것과 `deploy.sh --force` 로 다시 띄우는 것, 뜬 컨테이너가 그 키를 들고 있는지
확인하는 것까지 한 묶음입니다.

설정만 바꿔 다시 띄우고 싶을 때는 이것만으로도 됩니다.

```bash
~/where-is-my-book*/scripts/vm/deploy.sh --force
```

**값을 화면에 찍지 마세요.** 이 파일에는 정보나루 인증키가 함께 들어 있어서, 확인하려고
`cat ~/wimb.env` 하는 습관이 들면 그 화면이 갈무리되어 돌아다닙니다. `--show` 는 키
이름만 냅니다.

처음 만들 때는 본보기를 복사해서 시작합니다. **인증키만 빈칸이고 나머지는 그대로 쓸 수
있는 값이 들어 있습니다.**

```bash
cp ~/where-is-my-book*/scripts/vm/wimb.env.example ~/wimb.env
chmod 600 ~/wimb.env
~/where-is-my-book*/scripts/vm/set-env.sh D4L_AUTH_KEY=발급받은키
```

## 서가 받아 두기

사람이 여는 서가는 서버가 그때그때 세우므로 평소에는 할 일이 없습니다. 첫 사람을
기다리게 하고 싶지 않은 자리에만 미리 받습니다. **단위는 (도서관 × 대주제)입니다.**

```bash
~/where-is-my-book*/scripts/vm/harvest-shelf.sh 141321:8     # 그 도서관의 문학
```

**서비스하는 컨테이너는 건드리지 않습니다.** 따로 뜬 컨테이너가 받아서 다 만든 뒤에 한
번에 갈아 끼우므로, 그동안에도 서가는 예전 것으로 답합니다.

## 알아 둘 것

- **받기에 실패하면 돌던 서버를 건드리지 않습니다.** 먼저 받고 성공했을 때만 컨테이너를
  바꿉니다. 반대로 하면 레지스트리가 잠깐 흔들린 날 사이트가 통째로 내려갑니다.
- **돌고 있는 컨테이너와 비교합니다.** 받은 이미지가 그대로인지만 보면, 컨테이너가 죽어
  있거나 예전 이미지로 떠 있을 때 그것을 영영 고치지 못합니다.
- **테스트를 통과해야 이미지가 올라갑니다.** VM 이 2분마다 새 이미지를 물어 가므로, 깨진
  것을 올리면 그대로 서비스에 나갑니다. 자동화가 만든 위험이라 러너에서 막습니다.
- 문서와 프론트엔드만 고친 푸시로는 이미지를 다시 만들지 않습니다. 그런 변경은 API 서버와
  상관이 없습니다.
- 배포가 끝나면 오래된 이미지를 정리합니다. **30GB 디스크가 금방 찹니다.**
- 키는 `~/wimb.env` 에서 읽습니다. 이 파일이 없으면 배포가 실패합니다.
- **설정만 바꾼 것으로는 아무 일도 일어나지 않습니다.** 이미지가 그대로면 여기서 끝나므로,
  `--force` 를 붙이거나 `set-env.sh` 를 쓰세요. 위의 「설정 바꾸기」를 보세요.

## 다른 브랜치나 다른 이미지를 보게 하려면

```bash
systemctl --user edit wimb-deploy.service
```

```
[Service]
Environment=WIMB_IMAGE=ghcr.io/leeeeejy/where-is-my-book:어떤태그
```

## 손으로 만들어야 할 때

레지스트리를 못 쓰는 상황이면 VM 에서 직접 만들 수도 있습니다. 10~15분 걸립니다.

```bash
cd ~/where-is-my-book*/backend
docker build -t ghcr.io/leeeeejy/where-is-my-book:latest .
```

**그 전에 스왑을 잡아야 합니다.** 안에서 Gradle 이 도는데 컴파일만으로 1GB 를 넘겨
빌드가 죽습니다.
