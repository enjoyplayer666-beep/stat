СБОРКА (нужны JDK 21 и Maven):
  mvn package                    -> target/StatPlugin.jar
  mvn -f ranks/pom.xml package   -> ranks/target/DsRanks.jar
Оба файла положить в plugins/ и перезапустить сервер.

КОМАНДЫ:
  /stat <ник>                                  - показать статистику (всем)
  /statadmin set <ник> <поле> <значение>       - выдать значение (право stat.admin)
     поля: privilege, rating, winrate, deaths
  /statadmin reset <ник>
  /statadmin reload

ПРИМЕРЫ:
  /statadmin set antidepressant rating 500054
  /statadmin set antidepressant winrate 83%

БОЕВАЯ СТАТИСТИКА:
  /mystat                        - своя статистика
  За убийство игрока: +10-30 боевого рейтинга (БР).
  Процент побед (всегда целый): первое убийство - сразу 100% (выше не бывает), каждые 10 убийств +1%,
    каждые 10 смертей от игроков -2..3%. Сообщения о начисленном БР в чат не пишутся.
  Знаки классности по БР: 1 - Мастер, 100к - EXCLUSIVE, 250к - TIGR, 500к - LEGENDARY,
    1 млн - Immortal, 2 млн - Nephilim.
  Клан в /stat берётся из DestroyChat. Всё настраивается в config.yml.

РАНГИ - ОТДЕЛЬНЫЙ ПЛАГИН DsRanks (папка ranks/, собирается в DsRanks.jar):
  Ранги по убийствам, бустер x1-x15, умения ранга (атака/защита), /rank и /booster.
  /stat показывает ранг, убийства и бустер из DsRanks; ранг в чате DestroyChat тоже берёт из DsRanks.
  Плагины работают и по отдельности: без DsRanks в /stat вместо ранга прочерк,
  без StatPlugin ранги, /rank и /booster работают как обычно.
  При первом запуске DsRanks сам переносит из plugins/StatPlugin/ настройки рангов
  (config.yml) и убийства/бустеры/скрытие ранга игроков (data.yml).

  /rank info [ник]               - ранг, бустер, убийства, прогресс, умения
  /rank list                     - все ранги
  /rank top [страница]           - топ по убийствам
  /rank on | /rank off           - показывать ли свой ранг в чате
  /rank set <ник> <убийства>     - выставить убийства к рангу (право ranks.admin)
  /rank reset <ник>              - сбросить ранг и бустер (ranks.admin)
  /rank reload                   - перезагрузить конфиг DsRanks (ranks.admin)
  /booster give <ник> x15 | take <ник> | info <ник>  (право stat.booster.give, как раньше)
  Бустер по праву: stat.booster.2 ... stat.booster.15.
