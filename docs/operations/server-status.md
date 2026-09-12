# Состояние VPS

Запуск от `myvpn-deploy`, без sudo:

```bash
cd /opt/myvpn/myvpn
./scripts/server/status.sh
```

Коды выхода: `0` = OK, `1` = WARNING, `2` = ERROR.
Критическая ошибка даёт общий ERROR; остальные ошибки, WARNING и UNKNOWN — общий WARNING.

Скрипт только читает состояние: Docker-контейнеры backend и PostgreSQL,
HTTP health API, службу x-ui, TCP-порты 26810/2096/58131, свежесть последних
backup-файлов PostgreSQL и 3x-ui, backup timers, заполнение файловой системы
`/opt/myvpn` и сроки HTTPS-сертификатов api/panel/sub.
Недоступные из-за прав или отсутствия утилит проверки выводятся как UNKNOWN.
