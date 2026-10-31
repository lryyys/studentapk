# Study Assistant v2 (Чистая архитектура)

Проект полностью переписан с нуля. Без ИИ-модулей, без старых костылей и без `android-bridge`.

## Структура архива:
* `backend/` — единый Python-сервер (FastAPI + Telegram-бот aiogram 3 + APScheduler по часовому поясу Владивосток/Хабаровск UTC+10).
* `android/` — нативное Android-приложение на Kotlin и Jetpack Compose в стиле iOS + Material You.
* `.github/workflows/build-apk.yml` — автоматическая сборка APK в GitHub Actions.

## Быстрый запуск бэкенда:
1. Установите зависимости: `pip install -r backend/requirements.txt`
2. Задайте переменные окружения `BOT_TOKEN` и `ADMIN_TG_ID`.
3. Запустите: `uvicorn backend.main:app --host 0.0.0.0 --port 8080`
