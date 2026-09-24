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
