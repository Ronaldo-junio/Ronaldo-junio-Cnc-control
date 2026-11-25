# CNC em Rede (APK Android)

O celular fica ligado na CNC por cabo USB (OTG) e roda um servidor web na porta 8080.
Qualquer aparelho na mesma rede abre `http://IP-DO-CELULAR:8080`, digita o PIN e controla a máquina.

Assume firmware GRBL (Arduino/CH340/FTDI, 115200 baud). Marlin responde "ok" e deve funcionar no básico.

## Gerar o APK (GitHub Actions)
    cd CncRede
    git init && git add -A && git commit -m "cnc em rede"
    git branch -M main
    git remote add origin https://github.com/SEU_USUARIO/CncRede.git
    git push -u origin main
Depois: aba Actions -> "Build APK" -> artifact `cnc-rede-apk` -> app-debug.apk.

## Usar
1. Instale o APK, abra, conecte o cabo USB-OTG na CNC.
2. Toque em "Conectar à CNC (USB)" e aceite a permissão USB.
3. No outro aparelho, abra o endereço mostrado no app e digite o PIN.

## API (para scripts)  — enviar o PIN no header X-Pin
    curl -H "X-Pin: 1234" http://IP:8080/api/status
    curl -H "X-Pin: 1234" -X POST --data '$H' http://IP:8080/api/cmd
    curl -H "X-Pin: 1234" -X POST --data-binary @peca.nc http://IP:8080/api/job/start
Rotas: status, connect?baud=, disconnect, cmd, rt?c=hold|resume|reset|jogcancel|status,
job/start, job/pause, job/resume, job/stop.

## Segurança
Use só em rede local confiável. Não exponha a porta 8080 na internet.
Mantenha sempre o botão de emergência físico e alguém presente durante a usinagem.
