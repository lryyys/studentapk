import os
import secrets
import tempfile
import asyncio
from datetime import datetime, timezone, timedelta
from typing import Optional

from fastapi import FastAPI, Depends, HTTPException, Header, UploadFile, File
from pydantic import BaseModel
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker, Session

from aiogram import Bot, Dispatcher, F
from aiogram.types import Message, CallbackQuery, InlineKeyboardMarkup, InlineKeyboardButton
from aiogram.filters import Command
from apscheduler.schedulers.asyncio import AsyncIOScheduler

from models import Base, User, Lesson, Homework, Reminder
from excel_parser import parse_schedule_xlsx

BOT_TOKEN = os.getenv("BOT_TOKEN", "YOUR_BOT_TOKEN")
ADMIN_TG_ID = int(os.getenv("ADMIN_TG_ID", "0"))
DATABASE_URL = os.getenv("DATABASE_URL", "sqlite:///./study_assistant.db")
TZ_GMT10 = timezone(timedelta(hours=10))

engine = create_engine(DATABASE_URL, connect_args={"check_same_thread": False} if "sqlite" in DATABASE_URL else {})
SessionLocal = sessionmaker(bind=engine, autoflush=False, autocommit=False)
Base.metadata.create_all(bind=engine)

bot = Bot(token=BOT_TOKEN)
dp = Dispatcher()
scheduler = AsyncIOScheduler(timezone=TZ_GMT10)

def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()

def date_dmy_to_sort_key(date_str: str) -> int:
    try:
        d, m, y = date_str.strip().split(".")
        return int(f"{y}{int(m):02d}{int(d):02d}")
    except Exception:
        return 0

def save_parsed_lessons(db: Session, parsed_rows: list[dict]) -> int:
    dates_in_file = {r["date_str"] for r in parsed_rows}
    if dates_in_file:
        db.query(Lesson).filter(Lesson.date_str.in_(dates_in_file), Lesson.is_custom_override == False).delete(synchronize_session=False)
    added = 0
    for item in parsed_rows:
        exists = db.query(Lesson).filter_by(date_str=item["date_str"], start_time=item["start_time"], subject=item["subject"]).first()
        if not exists:
            db.add(Lesson(**item))
            added += 1
    db.commit()
    return added

async def check_and_send_reminders():
    now = datetime.now(TZ_GMT10)
    today_str = now.strftime("%d.%m.%Y")
    current_time_str = now.strftime("%H:%M")
    db = SessionLocal()
    try:
        due_reminders = db.query(Reminder).filter(
            Reminder.is_sent == False,
            Reminder.remind_date == today_str,
            Reminder.remind_time <= current_time_str
        ).all()
        for rem in due_reminders:
            text = f"⏰ <b>Напоминание</b> ({rem.remind_date} {rem.remind_time})\n\n{rem.title}"
            if rem.lesson_id:
                lesson = db.query(Lesson).filter_by(id=rem.lesson_id).first()
                if lesson:
                    text += f"\n\n📚 <b>Пара:</b> {lesson.subject} ({lesson.start_time}, {lesson.room})"
            if rem.homework_id:
                hw = db.query(Homework).filter_by(id=rem.homework_id).first()
                if hw:
                    text += f"\n📝 <b>ДЗ ({hw.subject}):</b> {hw.description}"
            try:
                await bot.send_message(rem.user_tg_id, text, parse_mode="HTML")
                rem.is_sent = True
            except Exception as e:
                print(f"Ошибка отправки напоминания {rem.id}: {e}")
        if due_reminders:
            db.commit()
    finally:
        db.close()

@dp.message(Command("start"))
async def cmd_start(message: Message):
    db = SessionLocal()
    try:
        tg_id = message.from_user.id
        user = db.query(User).filter_by(tg_id=tg_id).first()
        if not user:
            is_admin = (tg_id == ADMIN_TG_ID)
            user = User(
                tg_id=tg_id,
                username=message.from_user.username,
                full_name=message.from_user.full_name or "Студент",
                role="admin" if is_admin else "user",
                status="approved" if is_admin else "pending",
                api_token=secrets.token_hex(24) if is_admin else None
            )
            db.add(user)
            db.commit()
            db.refresh(user)
            if not is_admin and ADMIN_TG_ID:
                kb = InlineKeyboardMarkup(inline_keyboard=[[
                    InlineKeyboardButton(text="✅ Одобрить", callback_data=f"approve:{tg_id}"),
                    InlineKeyboardButton(text="🚫 Отклонить", callback_data=f"block:{tg_id}")
                ]])
                await bot.send_message(
                    ADMIN_TG_ID,
                    f"🔔 <b>Новая заявка на доступ:</b>\nИмя: {user.full_name}\nID: <code>{tg_id}</code>",
                    parse_mode="HTML",
                    reply_markup=kb
                )
        if user.status == "pending":
            await message.answer("⏳ Твоя заявка отправлена администратору. Ожидай подтверждения!")
            return
        if user.status == "blocked":
            await message.answer("🚫 Доступ ограничен.")
            return

        pair_code = f"{secrets.randbelow(900000) + 100000}"
        user.pair_code = pair_code
        if not user.api_token:
            user.api_token = secrets.token_hex(24)
        db.commit()

        await message.answer(
            f"👋 Привет, <b>{user.full_name}</b>!\n"
            f"📱 <b>Код для входа в Android-приложение:</b> <code>{pair_code}</code>",
            parse_mode="HTML"
        )
    finally:
        db.close()

@dp.message(F.document)
async def handle_excel_in_tg(message: Message):
    db = SessionLocal()
    try:
        user = db.query(User).filter_by(tg_id=message.from_user.id, role="admin").first()
        if not user or not message.document.file_name.endswith(".xlsx"):
            return
        file_info = await bot.get_file(message.document.file_id)
        with tempfile.NamedTemporaryFile(delete=False, suffix=".xlsx") as tmp:
            await bot.download_file(file_info.file_path, tmp.name)
            tmp_path = tmp.name
        parsed = parse_schedule_xlsx(tmp_path)
        os.remove(tmp_path)
        count = save_parsed_lessons(db, parsed)
        await message.answer(f"✅ Расписание обновлено! Пар: <b>{count}</b>", parse_mode="HTML")
    finally:
        db.close()

@asynccontextmanager
async def lifespan(app: FastAPI):
    scheduler.add_job(check_and_send_reminders, "interval", seconds=45)
    scheduler.start()
    polling_task = asyncio.create_task(dp.start_polling(bot, handle_signals=False))
    yield
    polling_task.cancel()
    scheduler.shutdown()
    await bot.session.close()

app = FastAPI(title="Study Assistant API v2", lifespan=lifespan)

def get_current_user(authorization: Optional[str] = Header(None), db: Session = Depends(get_db)) -> User:
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="Нет токена")
    token = authorization.removeprefix("Bearer ").strip()
    user = db.query(User).filter_by(api_token=token, status="approved").first()
    if not user:
        raise HTTPException(status_code=403, detail="Доступ запрещен")
    return user

def get_admin_user(user: User = Depends(get_current_user)) -> User:
    if user.role != "admin":
        raise HTTPException(status_code=403, detail="Нужны права админа")
    return user

class PairRequest(BaseModel):
    pair_code: str

class HomeworkCreate(BaseModel):
    date_str: str
    subject: str
    description: str

class ReminderCreate(BaseModel):
    title: str
    remind_date: str
    remind_time: str
    lesson_id: Optional[int] = None
    homework_id: Optional[int] = None

class LessonUpdate(BaseModel):
    date_str: str
    day_label: str
    start_time: str
    end_time: str
    lesson_type: str
    subject: str
    teacher: str = ""
    room: str = ""

class BroadcastRequest(BaseModel):
    message_text: str

@app.post("/api/auth/pair")
def auth_by_pair_code(req: PairRequest, db: Session = Depends(get_db)):
    code = req.pair_code.strip()
    user = db.query(User).filter_by(pair_code=code, status="approved").first()
    if not user:
        raise HTTPException(status_code=400, detail="Неверный код")
    if not user.api_token:
        user.api_token = secrets.token_hex(24)
    user.pair_code = None
    db.commit()
    return {"api_token": user.api_token, "tg_id": user.tg_id, "full_name": user.full_name, "role": user.role}

@app.get("/api/sync")
def sync_all_data(user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    lessons = db.query(Lesson).order_by(Lesson.sort_key, Lesson.start_time).all()
    homework = db.query(Homework).order_by(Homework.id.desc()).all()
    reminders = db.query(Reminder).filter_by(user_tg_id=user.tg_id).order_by(Reminder.id.desc()).all()
    return {
        "user": {"tg_id": user.tg_id, "full_name": user.full_name, "role": user.role},
        "lessons": [{"id": l.id, "date_str": l.date_str, "day_label": l.day_label, "start_time": l.start_time, "end_time": l.end_time, "lesson_type": l.lesson_type, "subject": l.subject, "teacher": l.teacher, "room": l.room} for l in lessons],
        "homework": [{"id": h.id, "date_str": h.date_str, "subject": h.subject, "description": h.description, "created_at": h.created_at} for h in homework],
        "reminders": [{"id": r.id, "title": r.title, "remind_date": r.remind_date, "remind_time": r.remind_time, "is_sent": r.is_sent} for r in reminders]
    }

@app.post("/api/homework")
def create_homework(data: HomeworkCreate, user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    hw = Homework(date_str=data.date_str, subject=data.subject, description=data.description, created_by=user.tg_id)
    db.add(hw)
    db.commit()
    return {"status": "ok", "id": hw.id}

@app.delete("/api/homework/{hw_id}")
def delete_homework(hw_id: int, user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    hw = db.query(Homework).filter_by(id=hw_id).first()
    if hw:
        db.delete(hw)
        db.commit()
    return {"status": "ok"}

@app.post("/api/reminders")
def create_reminder(data: ReminderCreate, user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    rem = Reminder(user_tg_id=user.tg_id, title=data.title, remind_date=data.remind_date, remind_time=data.remind_time, lesson_id=data.lesson_id, homework_id=data.homework_id)
    db.add(rem)
    db.commit()
    return {"status": "ok", "id": rem.id}

@app.delete("/api/reminders/{rem_id}")
def delete_reminder(rem_id: int, user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    rem = db.query(Reminder).filter_by(id=rem_id, user_tg_id=user.tg_id).first()
    if rem:
        db.delete(rem)
        db.commit()
    return {"status": "ok"}

@app.post("/api/admin/schedule/upload")
async def upload_schedule_from_android(file: UploadFile = File(...), admin: User = Depends(get_admin_user), db: Session = Depends(get_db)):
    with tempfile.NamedTemporaryFile(delete=False, suffix=".xlsx") as tmp:
        tmp.write(await file.read())
        tmp_path = tmp.name
    parsed = parse_schedule_xlsx(tmp_path)
    os.remove(tmp_path)
    count = save_parsed_lessons(db, parsed)
    return {"status": "ok", "imported_lessons": count}

@app.put("/api/admin/schedule/lesson")
def upsert_custom_lesson(data: LessonUpdate, admin: User = Depends(get_admin_user), db: Session = Depends(get_db)):
    sort_key = date_dmy_to_sort_key(data.date_str)
    lesson = db.query(Lesson).filter_by(date_str=data.date_str, start_time=data.start_time, subject=data.subject).first()
    if lesson:
        lesson.end_time = data.end_time
        lesson.lesson_type = data.lesson_type
        lesson.teacher = data.teacher
        lesson.room = data.room
        lesson.is_custom_override = True
    else:
        lesson = Lesson(date_str=data.date_str, sort_key=sort_key, day_label=data.day_label, start_time=data.start_time, end_time=data.end_time, lesson_type=data.lesson_type, subject=data.subject, teacher=data.teacher, room=data.room, is_custom_override=True)
        db.add(lesson)
    db.commit()
    return {"status": "ok"}

@app.get("/api/admin/users")
def list_users(admin: User = Depends(get_admin_user), db: Session = Depends(get_db)):
    users = db.query(User).order_by(User.id.desc()).all()
    return [{"tg_id": u.tg_id, "username": u.username, "full_name": u.full_name, "role": u.role, "status": u.status} for u in users]

@app.post("/api/admin/users/{target_tg_id}/status")
async def set_user_status(target_tg_id: int, status: str, admin: User = Depends(get_admin_user), db: Session = Depends(get_db)):
    user = db.query(User).filter_by(tg_id=target_tg_id).first()
    if user:
        user.status = status
        if status == "approved" and not user.api_token:
            user.api_token = secrets.token_hex(24)
            user.pair_code = f"{secrets.randbelow(900000) + 100000}"
        db.commit()
    return {"status": "ok"}

@app.post("/api/admin/broadcast")
async def broadcast_message(req: BroadcastRequest, admin: User = Depends(get_admin_user), db: Session = Depends(get_db)):
    users = db.query(User).filter_by(status="approved").all()
    sent = 0
    for u in users:
        try:
            await bot.send_message(u.tg_id, f"📢 <b>Объявление:</b>\n\n{req.message_text}", parse_mode="HTML")
            sent += 1
        except Exception:
            pass
    return {"status": "ok", "sent_count": sent}
