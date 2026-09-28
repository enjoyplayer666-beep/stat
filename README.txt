СБОРКА (нужны JDK 21 и Maven):
  mvn package
Готовый файл: target/StatPlugin.jar -> положить в plugins/ и перезапустить сервер.

КОМАНДЫ:
  /stat <ник>                                  - показать статистику (всем)
  /statadmin set <ник> <поле> <значение>       - выдать значение (право stat.admin)
     поля: privilege, rank, clan, rating, class, winrate
  /statadmin reset <ник>
  /statadmin reload

ПРИМЕРЫ:
  /statadmin set antidepressant rank &7☢ &fСтраж Небытия
  /statadmin set antidepressant rating 500054
  /statadmin set antidepressant class &4⚔&c&lLEGENDARY&4⚔
  /statadmin set antidepressant winrate 83%
  /statadmin set antidepressant clan Øponimaniya

РАНГИ И БОЕВАЯ СТАТИСТИКА (1.1):
  /mystat                        - своя статистика
  /rank info [ник]               - ранг, бустер, убийства, прогресс, умения
  /rank list                     - все 17 рангов
  /rank top [страница]           - топ по убийствам
  /rank on | /rank off           - показывать ли свой ранг в чате (DestroyChat, перед кланом)

  За убийство игрока: +10-30 боевого рейтинга (БР), +1 убийство к рангу.
  Бустер x1-x15: с шансом booster.chance убийство засчитывается за xN (в чат пишется "Сработал бустер").
    Выдать: /statadmin set <ник> booster 15   или право stat.booster.15
  Процент побед: первое убийство - сразу 100% (выше не бывает), каждые 10 убийств +1%,
    каждые 10 смертей от игроков -2..3%. Сообщения о начисленном БР в чат не пишутся.
  Знаки классности по БР: 1 - Мастер, 100к - EXCLUSIVE, 250к - TIGR, 500к - LEGENDARY,
    1 млн - Immortal, 2 млн - Nephilim.
  Умения ранга: атака до +100% урона (шанс до 33%), защита до -50% урона (шанс до 33%).
  Клан в /stat берётся из DestroyChat. Всё настраивается в config.yml.
