Sprite Calendar (без drag-and-drop)
===================================

Окно 1280x720. Клик по дню открывает «Меню дня».

Меню дня
--------
• Список событий (центр)
• Опрос сверху (если есть)
• Кнопки снизу:
  - «Добавить событие» — текст, тип, цвет. Типы сохраняются.
  - «Добавить опрос» — анонимный, сразу фейковые голоса = 1 + число удалённых дней.
  - «Удалить день» — анимация (спрайты), затем день пропадает навсегда.
    В calendar_save.dat пишется SHA-256 хэш дня — откатить сложно.
    После перезапуска удалённые дни не возвращаются.

Спрайты (src/sprites/ → out/sprites/)
-------------------------------------
digit_0.png … digit_9.png
day_bg_1.png
day_bg_2.png
day_bg_3.png          ← обычный день: случайный из трёх
day_bg_selected.png   ← сегодня (опционально)
arrow_left.png
arrow_right.png
month_label.png       (опционально)

delete_anim/
  0.png, 1.png, 2.png …   ← кадры анимации удаления (по порядку)

Запуск
------
mkdir out
javac -encoding UTF-8 -d out src\com\calendar\SpriteCalendar.java
mkdir out\sprites
xcopy /E /I src\sprites out\sprites
java -cp out com.calendar.SpriteCalendar

Файл сохранения calendar_save.dat появляется в папке запуска.
